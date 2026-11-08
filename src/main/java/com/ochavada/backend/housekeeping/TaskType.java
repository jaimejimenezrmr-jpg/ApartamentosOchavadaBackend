package com.ochavada.backend.housekeeping;

public enum TaskType {
    /** Limpieza completa tras una salida. */
    CHECKOUT_CLEAN,
    /** Repaso durante la estancia. */
    STAYOVER_CLEAN,
    DEEP_CLEAN,
    INSPECTION,
    MAINTENANCE,
    LINEN_CHANGE;

    /** Tareas que, al completarse, dejan la unidad limpia. */
    public boolean cleansRoom() {
        return this != MAINTENANCE && this != INSPECTION;
    }
}
