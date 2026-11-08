package com.ochavada.backend.access;

public enum LockType {
    /** Portal del edificio: la abren todos los huéspedes alojados. */
    MAIN_ENTRANCE,
    /** Puerta de una unidad: solo la abre el huésped alojado en ella. */
    ROOM,
    /** Zonas comunes (piscina, trastero...): solo personal. */
    COMMON_AREA
}
