package com.ochavada.backend.rates;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.rates.dto.RateSeasonRequest;
import com.ochavada.backend.rates.dto.RateSeasonResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateSeasonService {

    static final int MAX_SEASON_NIGHTS = 731;

    private final RateSeasonRepository repository;
    private final RatePlanRepository ratePlanRepository;

    private final ApplicationEventPublisher events;

    public RateSeasonService(RateSeasonRepository repository, RatePlanRepository ratePlanRepository,
                             ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.ratePlanRepository = ratePlanRepository;
    }

    @Transactional(readOnly = true)
    public List<RateSeasonResponse> list(UUID ratePlanId) {
        findPlan(ratePlanId);
        return repository.findByRatePlanIdOrderByStartDateAsc(ratePlanId).stream()
                .map(RateSeasonResponse::from)
                .toList();
    }

    @Transactional
    public RateSeasonResponse create(UUID ratePlanId, RateSeasonRequest request) {
        RatePlan plan = findPlan(ratePlanId);
        LocalDate start = request.firstNight();
        LocalDate end = request.lastNight().plusDays(1);
        validateLength(start, end);
        if (repository.existsOverlap(ratePlanId, start, end)) {
            throw overlap();
        }
        RateSeason season = new RateSeason(plan);
        apply(season, request, start, end);
        RateSeasonResponse response = RateSeasonResponse.from(repository.saveAndFlush(season));
        events.publishEvent(InventoryChangedEvent.nights(plan.getRoomType().getId(), start, end));
        return response;
    }

    @Transactional
    public RateSeasonResponse update(UUID ratePlanId, UUID seasonId, RateSeasonRequest request) {
        RateSeason season = find(ratePlanId, seasonId);
        season.assertVersion(request.version());
        LocalDate start = request.firstNight();
        LocalDate end = request.lastNight().plusDays(1);
        validateLength(start, end);
        if (repository.existsOverlapExcluding(ratePlanId, start, end, seasonId)) {
            throw overlap();
        }
        LocalDate previousStart = season.getStartDate();
        LocalDate previousEnd = season.getEndDate();
        apply(season, request, start, end);
        RateSeasonResponse response = RateSeasonResponse.from(repository.saveAndFlush(season));
        UUID roomTypeId = season.getRatePlan().getRoomType().getId();
        events.publishEvent(InventoryChangedEvent.nights(roomTypeId, previousStart, previousEnd));
        events.publishEvent(InventoryChangedEvent.nights(roomTypeId, start, end));
        return response;
    }

    @Transactional
    public void delete(UUID ratePlanId, UUID seasonId) {
        RateSeason season = find(ratePlanId, seasonId);
        InventoryChangedEvent changed = InventoryChangedEvent.nights(season.getRatePlan().getRoomType().getId(),
                season.getStartDate(), season.getEndDate());
        repository.delete(season);
        repository.flush();
        events.publishEvent(changed);
    }

    private RateSeason find(UUID ratePlanId, UUID seasonId) {
        return repository.findById(seasonId)
                .filter(s -> s.getRatePlan().getId().equals(ratePlanId))
                .orElseThrow(() -> new ResourceNotFoundException("temporada", seasonId));
    }

    private RatePlan findPlan(UUID ratePlanId) {
        return ratePlanRepository.findById(ratePlanId)
                .orElseThrow(() -> new ResourceNotFoundException("plan de tarifa", ratePlanId));
    }

    private static void validateLength(LocalDate start, LocalDate end) {
        if (ChronoUnit.DAYS.between(start, end) > MAX_SEASON_NIGHTS) {
            throw new BadRequestException("rate_season.too_long",
                    "Una temporada no puede superar " + MAX_SEASON_NIGHTS + " noches");
        }
    }

    private static void apply(RateSeason season, RateSeasonRequest r, LocalDate start, LocalDate end) {
        season.setName(r.name().trim());
        season.setStartDate(start);
        season.setEndDate(end);
        season.setNightlyPrice(r.nightlyPrice());
        season.setWeekendPrice(r.weekendPrice());
        season.setMinStay(r.minStay());
        season.setMaxStay(r.maxStay());
    }

    private static ConflictException overlap() {
        return new ConflictException("rate_season.overlap",
                "Las fechas se solapan con otra temporada del mismo plan de tarifa");
    }
}
