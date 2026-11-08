package com.ochavada.backend.availability;

import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.rates.DailyRate;
import com.ochavada.backend.rates.DailyRateRepository;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RateSeason;
import com.ochavada.backend.rates.RateSeasonRepository;
import com.ochavada.backend.rates.pricing.DayOverride;
import com.ochavada.backend.rates.pricing.SeasonRate;
import com.ochavada.backend.rates.pricing.StayPricingEngine;
import com.ochavada.backend.rates.pricing.StayQuote;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcula presupuestos de estancia para varios planes cargando sus temporadas y ajustes en dos
 * consultas (sin N+1). Lo reutilizarán el buscador público y el checkout, que debe recalcular el
 * precio en el servidor y nunca fiarse del importe enviado por el navegador.
 */
@Service
public class StayQuoteService {

    private final RateSeasonRepository seasonRepository;
    private final DailyRateRepository dailyRateRepository;
    private final StayPricingEngine engine;
    private final PropertyCalendar calendar;

    public StayQuoteService(RateSeasonRepository seasonRepository, DailyRateRepository dailyRateRepository,
                            StayPricingEngine engine, PropertyCalendar calendar) {
        this.seasonRepository = seasonRepository;
        this.dailyRateRepository = dailyRateRepository;
        this.engine = engine;
        this.calendar = calendar;
    }

    /** Presupuestos indexados por id del plan de tarifa. */
    @Transactional(readOnly = true)
    public Map<UUID, StayQuote> quote(Collection<RatePlan> plans, LocalDate checkIn, LocalDate checkOut) {
        Map<UUID, StayQuote> result = new LinkedHashMap<>();
        if (plans.isEmpty()) {
            return result;
        }
        List<UUID> planIds = plans.stream().map(RatePlan::getId).toList();

        Map<UUID, List<SeasonRate>> seasonsByPlan = new HashMap<>();
        for (RateSeason season : seasonRepository.findOverlapping(planIds, checkIn, checkOut.plusDays(1))) {
            seasonsByPlan.computeIfAbsent(season.getRatePlan().getId(), id -> new ArrayList<>())
                    .add(season.toSeasonRate());
        }
        // Se incluye el día de salida: su CTD también se evalúa.
        Map<UUID, Map<LocalDate, DayOverride>> overridesByPlan = dailyRateRepository
                .findInRange(planIds, checkIn, checkOut).stream()
                .collect(Collectors.groupingBy(d -> d.getRatePlan().getId(),
                        Collectors.toMap(DailyRate::getStayDate, DailyRate::toOverride)));

        LocalDate today = calendar.today();
        for (RatePlan plan : plans) {
            result.put(plan.getId(), engine.quote(checkIn, checkOut, today, plan.terms(),
                    seasonsByPlan.getOrDefault(plan.getId(), List.of()),
                    overridesByPlan.getOrDefault(plan.getId(), Map.of())));
        }
        return result;
    }
}
