package com.ochavada.backend.access.dto;

import com.ochavada.backend.access.DoorKind;
import com.ochavada.backend.reservation.ReservationStatus;
import java.time.Instant;
import java.util.List;

/** DTOs del portal del huésped para las puertas. */
public final class DoorDtos {

    private DoorDtos() {
    }

    /**
     * Estado de los botones del portal.
     *
     * @param selfCheckIn si el huésped puede hacer el check-in él mismo y, si no, por qué
     */
    public record DoorsResponse(
            String reservationCode,
            ReservationStatus status,
            Instant accessFrom,
            Instant accessUntil,
            SelfCheckIn selfCheckIn,
            List<Door> doors) {
    }

    public record SelfCheckIn(boolean available, List<String> blockers) {
    }

    /** {@code reason}: motivo por el que el botón está deshabilitado (nulo si está disponible). */
    public record Door(DoorKind kind, String name, boolean available, String reason) {
    }

    public record UnlockResponse(DoorKind kind, String doorName, String result, Instant openedAt) {
    }
}
