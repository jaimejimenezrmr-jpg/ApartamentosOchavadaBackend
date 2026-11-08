package com.ochavada.backend.rates;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.rates.dto.DailyRateBulkRequest;
import com.ochavada.backend.rates.dto.DailyRateBulkRequest.ClearableField;
import com.ochavada.backend.rates.dto.DailyRateBulkResult;
import com.ochavada.backend.security.CurrentUser;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edición masiva de ajustes diarios de precio y restricciones.
 */
@Service
public class DailyRateService {

    static final int MAX_RANGE_DAYS = 731;

    private final DailyRateRepository repository;
    private final RatePlanRepository ratePlanRepository;
    private final PropertyCalendar calendar;

    private final ApplicationEventPublisher events;

    public DailyRateService(DailyRateRepository repository, RatePlanRepository ratePlanRepository,
                            PropertyCalendar calendar, ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.ratePlanRepository = ratePlanRepository;
        this.calendar = calendar;
    }

    @Transactional
    public DailyRateBulkResult apply(UUID ratePlanId, DailyRateBulkRequest request) {
        RatePlan plan = ratePlanRepository.findById(ratePlanId)
                .orElseThrow(() -> new ResourceNotFoundException("plan de tarifa", ratePlanId));
        LocalDate first = request.firstNight();
        LocalDate last = request.lastNight();
        if (first.isBefore(calendar.today())) {
            throw new BadRequestException("daily_rate.in_past", "No se pueden modificar tarifas de días pasados");
        }
        if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_RANGE_DAYS) {
            throw new BadRequestException("daily_rate.range_too_long",
                    "El rango no puede superar " + MAX_RANGE_DAYS + " días");
        }

        Map<LocalDate, DailyRate> existing = repository.findInRange(List.of(ratePlanId), first, last).stream()
                .collect(Collectors.toMap(DailyRate::getStayDate, Function.identity()));
        Set<ClearableField> clear = request.clearOrEmpty();
        UUID userId = CurrentUser.staffUserId().orElse(null);

        List<DailyRate> toSave = new ArrayList<>();
        List<DailyRate> toDelete = new ArrayList<>();
        int affected = 0;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (request.daysOfWeek() != null && !request.daysOfWeek().isEmpty()
                    && !request.daysOfWeek().contains(date.getDayOfWeek())) {
                continue;
            }
            affected++;
            DailyRate rate = existing.get(date);
            if (rate == null) {
                rate = new DailyRate(plan, date);
            }
            applyChanges(rate, request, clear);
            if (rate.getMinStay() != null && rate.getMaxStay() != null && rate.getMaxStay() < rate.getMinStay()) {
                throw new BadRequestException("daily_rate.invalid_stay",
                        "El " + date + " la estancia máxima quedaría por debajo de la mínima");
            }
            rate.setUpdatedByUserId(userId);
            if (rate.isEmpty()) {
                if (rate.getId() != null) {
                    toDelete.add(rate);
                }
            } else {
                toSave.add(rate);
            }
        }
        repository.deleteAll(toDelete);
        repository.saveAll(toSave);
        repository.flush();
        events.publishEvent(InventoryChangedEvent.days(plan.getRoomType().getId(), first, last));
        return new DailyRateBulkResult(affected, toDelete.size());
    }

    private static void applyChanges(DailyRate rate, DailyRateBulkRequest request, Set<ClearableField> clear) {
        if (request.price() != null) {
            rate.setPrice(request.price());
        } else if (clear.contains(ClearableField.PRICE)) {
            rate.setPrice(null);
        }
        if (request.minStay() != null) {
            rate.setMinStay(request.minStay());
        } else if (clear.contains(ClearableField.MIN_STAY)) {
            rate.setMinStay(null);
        }
        if (request.maxStay() != null) {
            rate.setMaxStay(request.maxStay());
        } else if (clear.contains(ClearableField.MAX_STAY)) {
            rate.setMaxStay(null);
        }
        if (request.closedToArrival() != null) {
            rate.setClosedToArrival(request.closedToArrival());
        }
        if (request.closedToDeparture() != null) {
            rate.setClosedToDeparture(request.closedToDeparture());
        }
        if (request.stopSell() != null) {
            rate.setStopSell(request.stopSell());
        }
    }
}
