package com.ochavada.backend.rates;

import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.rates.dto.RateCalendarResponse;
import com.ochavada.backend.rates.pricing.DayOverride;
import com.ochavada.backend.rates.pricing.DayRate;
import com.ochavada.backend.rates.pricing.RatePlanTerms;
import com.ochavada.backend.rates.pricing.SeasonRate;
import com.ochavada.backend.rates.pricing.StayPricingEngine;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calendario de tarifas del backoffice: para cada día, precio y restricciones efectivos tras la
 * precedencia ajuste &gt; temporada &gt; plan, y unidades libres de la tipología.
 */
@Service
public class RateCalendarService {

    static final int MAX_CALENDAR_DAYS = 366;

    private final RatePlanRepository ratePlanRepository;
    private final RateSeasonRepository seasonRepository;
    private final DailyRateRepository dailyRateRepository;
    private final InventoryRepository inventoryRepository;
    private final StayPricingEngine engine;

    public RateCalendarService(RatePlanRepository ratePlanRepository, RateSeasonRepository seasonRepository,
                               DailyRateRepository dailyRateRepository, InventoryRepository inventoryRepository,
                               StayPricingEngine engine) {
        this.ratePlanRepository = ratePlanRepository;
        this.seasonRepository = seasonRepository;
        this.dailyRateRepository = dailyRateRepository;
        this.inventoryRepository = inventoryRepository;
        this.engine = engine;
    }

    /** Días de {@code from} a {@code to}, ambos incluidos. */
    @Transactional(readOnly = true)
    public RateCalendarResponse calendar(UUID ratePlanId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("validation.date_range", "La fecha 'to' no puede ser anterior a 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_CALENDAR_DAYS) {
            throw new BadRequestException("validation.date_range",
                    "El calendario no puede superar " + MAX_CALENDAR_DAYS + " días");
        }
        RatePlan plan = ratePlanRepository.findById(ratePlanId)
                .orElseThrow(() -> new ResourceNotFoundException("plan de tarifa", ratePlanId));
        LocalDate toExclusive = to.plusDays(1);

        RatePlanTerms terms = plan.terms();
        List<SeasonRate> seasons = seasonRepository.findOverlapping(List.of(ratePlanId), from, toExclusive).stream()
                .map(RateSeason::toSeasonRate)
                .toList();
        Map<LocalDate, DayOverride> overrides = dailyRateRepository.findInRange(List.of(ratePlanId), from, to).stream()
                .collect(Collectors.toMap(DailyRate::getStayDate, DailyRate::toOverride));
        Map<LocalDate, Integer> availability = inventoryRepository.nightlyAvailability(
                plan.getRoomType().getId(), from, toExclusive);

        List<RateCalendarResponse.Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DayRate rate = engine.resolveDay(date, terms, seasons, overrides);
            days.add(new RateCalendarResponse.Day(date, rate.price(), rate.priceSource(), rate.minStay(),
                    rate.maxStay(), rate.closedToArrival(), rate.closedToDeparture(), rate.stopSell(),
                    availability.getOrDefault(date, 0)));
        }
        return new RateCalendarResponse(plan.getId(), plan.getRoomType().getId(), plan.getRoomType().getCurrency(),
                days);
    }
}
