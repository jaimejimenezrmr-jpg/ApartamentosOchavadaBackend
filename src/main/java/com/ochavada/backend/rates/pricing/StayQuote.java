package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Presupuesto de una estancia para un plan de tarifa.
 *
 * @param nights                 desglose por noche
 * @param totalAmount            suma de las noches
 * @param averageNightlyPrice    total / noches
 * @param prepaymentAmount       importe a cobrar al reservar
 * @param freeCancellationUntil  último día (incluido, hora local del alojamiento) con cancelación gratuita;
 *                               {@code null} si la tarifa no es reembolsable o el plazo ya ha pasado
 * @param violations             restricciones incumplidas; vacía si es reservable
 */
public record StayQuote(
        LocalDate checkIn,
        LocalDate checkOut,
        List<DayRate> nights,
        BigDecimal totalAmount,
        BigDecimal averageNightlyPrice,
        BigDecimal prepaymentAmount,
        LocalDate freeCancellationUntil,
        List<RestrictionViolation> violations) {

    public boolean bookable() {
        return violations.isEmpty();
    }

    public int nightCount() {
        return nights.size();
    }
}
