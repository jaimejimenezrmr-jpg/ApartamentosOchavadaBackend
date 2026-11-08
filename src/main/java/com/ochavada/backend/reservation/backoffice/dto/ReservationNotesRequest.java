package com.ochavada.backend.reservation.backoffice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** Datos editables sin efecto en precio ni inventario. Sustituye los valores actuales. */
public record ReservationNotesRequest(
        @Size(max = 2000) String specialRequests,
        @Size(max = 5000) String internalNotes,
        LocalTime expectedArrivalTime,
        @Size(max = 100) String channelReference,
        @NotNull Long version) {
}
