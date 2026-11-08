package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ajuste diario: los campos nulos no sobrescriben a la temporada ni al plan. */
public record DayOverride(
        LocalDate date,
        BigDecimal price,
        Integer minStay,
        Integer maxStay,
        boolean closedToArrival,
        boolean closedToDeparture,
        boolean stopSell) {
}
