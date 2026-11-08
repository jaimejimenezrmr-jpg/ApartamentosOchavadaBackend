package com.ochavada.backend.rates.pricing;

import java.time.LocalDate;

/**
 * Motivo por el que una tarifa no es reservable para la estancia pedida.
 *
 * @param value valor de la restricción cuando aplica (noches mínimas o máximas)
 * @param date  día al que se refiere la restricción cuando aplica
 */
public record RestrictionViolation(RestrictionCode code, String message, Integer value, LocalDate date) {
}
