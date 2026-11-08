package com.ochavada.backend.rates.pricing;

/** Origen del precio resuelto para una noche (útil para el calendario de tarifas del backoffice). */
public enum PriceSource {
    /** Ajuste diario explícito. */
    DAILY_OVERRIDE,
    /** Precio de fin de semana de la temporada. */
    SEASON_WEEKEND,
    /** Precio por noche de la temporada. */
    SEASON,
    /** Precio base del plan. */
    BASE
}
