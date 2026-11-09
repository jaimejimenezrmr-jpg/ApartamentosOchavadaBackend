package com.ochavada.backend.housekeeping.dto;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.RoomStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Estado de una unidad para el personal: limpieza, ocupación y lo que pasa hoy.
 *
 * @param occupied       hay un huésped alojado (check-in hecho)
 * @param departureToday sale hoy un huésped
 * @param arrivalToday   llega hoy un huésped (la unidad debe quedar LISTA)
 * @param openIncidents  incidencias abiertas o en curso
 */
public record RoomBoardItem(
        UUID roomId,
        String number,
        String name,
        String roomTypeName,
        Integer floor,
        RoomStatus status,
        HousekeepingStatus housekeepingStatus,
        Instant housekeepingUpdatedAt,
        boolean occupied,
        boolean departureToday,
        boolean arrivalToday,
        long openIncidents) {
}
