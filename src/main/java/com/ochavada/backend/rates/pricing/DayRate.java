package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Precio y restricciones efectivos de un día tras aplicar la precedencia
 * ajuste diario &gt; temporada &gt; plan.
 * <p>
 * {@code minStay}/{@code maxStay} y {@code closedToArrival} se evalúan sobre el día de llegada;
 * {@code closedToDeparture} sobre el día de salida; {@code stopSell} sobre cada noche.
 */
public record DayRate(
        LocalDate date,
        BigDecimal price,
        PriceSource priceSource,
        int minStay,
        Integer maxStay,
        boolean closedToArrival,
        boolean closedToDeparture,
        boolean stopSell) {
}
