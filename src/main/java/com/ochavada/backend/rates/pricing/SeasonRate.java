package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Temporada aplicable a las noches de {@code [startDate, endDate)}. */
public record SeasonRate(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal nightlyPrice,
        BigDecimal weekendPrice,
        Integer minStay,
        Integer maxStay) {

    public SeasonRate {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(nightlyPrice, "nightlyPrice");
    }

    public boolean covers(LocalDate night) {
        return !night.isBefore(startDate) && night.isBefore(endDate);
    }
}
