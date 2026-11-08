package com.ochavada.backend.rates.pricing;

public enum RestrictionCode {
    /** Alguna noche de la estancia está cerrada a la venta. */
    STOP_SELL,
    /** No se admiten llegadas el día de entrada. */
    CLOSED_TO_ARRIVAL,
    /** No se admiten salidas el día de salida. */
    CLOSED_TO_DEPARTURE,
    /** Estancia inferior a la mínima exigida para el día de llegada. */
    MIN_STAY,
    /** Estancia superior a la máxima permitida para el día de llegada. */
    MAX_STAY
}
