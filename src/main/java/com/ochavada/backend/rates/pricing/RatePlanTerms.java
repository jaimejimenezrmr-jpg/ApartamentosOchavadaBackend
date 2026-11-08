package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Condiciones de un plan de tarifa relevantes para el cálculo de precios. */
public record RatePlanTerms(
        UUID ratePlanId,
        BigDecimal basePrice,
        int minStay,
        Integer maxStay,
        BigDecimal prepaymentPercent,
        boolean refundable,
        Integer freeCancellationDays) {

    public RatePlanTerms {
        Objects.requireNonNull(basePrice, "basePrice");
        Objects.requireNonNull(prepaymentPercent, "prepaymentPercent");
    }
}
