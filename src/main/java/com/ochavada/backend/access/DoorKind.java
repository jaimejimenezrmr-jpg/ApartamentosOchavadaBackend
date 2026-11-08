package com.ochavada.backend.access;

import com.ochavada.backend.common.error.BadRequestException;

/** Los dos botones del portal del huésped. */
public enum DoorKind {
    /** «Abrir Entrada Principal». */
    MAIN_ENTRANCE("main-entrance"),
    /** «Abrir Mi Habitación». */
    ROOM("room");

    private final String pathSegment;

    DoorKind(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public static DoorKind fromPath(String segment) {
        for (DoorKind kind : values()) {
            if (kind.pathSegment.equals(segment)) {
                return kind;
            }
        }
        throw new BadRequestException("access.unknown_door", "Puerta desconocida: use main-entrance o room");
    }
}
