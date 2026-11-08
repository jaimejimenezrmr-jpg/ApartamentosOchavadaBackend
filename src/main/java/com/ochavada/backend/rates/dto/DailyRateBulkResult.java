package com.ochavada.backend.rates.dto;

/**
 * @param affectedDays      días del rango a los que se aplicó el cambio
 * @param removedOverrides  ajustes que quedaron vacíos y se eliminaron
 */
public record DailyRateBulkResult(int affectedDays, int removedOverrides) {
}
