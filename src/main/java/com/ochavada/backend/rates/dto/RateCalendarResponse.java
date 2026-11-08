package com.ochavada.backend.rates.dto;

import com.ochavada.backend.rates.pricing.PriceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Calendario de tarifas de un plan: precio y restricciones efectivos y unidades libres por día. */
public record RateCalendarResponse(
        UUID ratePlanId,
        UUID roomTypeId,
        String currency,
        List<Day> days) {

    public record Day(
            LocalDate date,
            BigDecimal price,
            PriceSource priceSource,
            int minStay,
            Integer maxStay,
            boolean closedToArrival,
            boolean closedToDeparture,
            boolean stopSell,
            int availableUnits) {
    }
}
