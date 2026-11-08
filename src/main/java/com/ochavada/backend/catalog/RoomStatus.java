package com.ochavada.backend.catalog;

/** Estado operativo de una unidad. Solo las {@code ACTIVE} cuentan como inventario vendible. */
public enum RoomStatus {
    ACTIVE,
    /** Fuera de servicio temporalmente (avería, obras). */
    OUT_OF_ORDER,
    /** Dada de baja del inventario. */
    INACTIVE
}
