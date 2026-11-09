package com.ochavada.backend.incident;

/** Quién detectó la incidencia. */
public enum IncidentOrigin {
    /** Limpieza, desde la lista de comprobación o el tablero. */
    HOUSEKEEPING,
    /** Recepción o administración. */
    STAFF,
    /** Comunicada por un huésped (la registra el personal). */
    GUEST
}
