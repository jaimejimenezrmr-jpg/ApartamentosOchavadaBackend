package com.ochavada.backend.pms.dto;

import com.ochavada.backend.catalog.BlockType;
import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.reservation.ReservationChannel;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Rack de disponibilidad (diagrama de Gantt): una fila por unidad con sus reservas y bloqueos, y aparte
 * las reservas pendientes de asignar unidad. Todas las fechas son intervalos {@code [inicio, fin)}.
 */
public record RackResponse(
        LocalDate from,
        LocalDate to,
        List<RackRoomType> roomTypes,
        List<RackBooking> unassigned) {

    public record RackRoomType(UUID id, String name, List<RackRoom> rooms) {
    }

    public record RackRoom(UUID id, String number, String name, RoomStatus status,
                           HousekeepingStatus housekeepingStatus, List<RackBooking> bookings, List<RackBlock> blocks) {
    }

    public record RackBooking(UUID reservationId, String code, ReservationStatus status, ReservationChannel channel,
                              String guestName, LocalDate checkIn, LocalDate checkOut, int adults, int children,
                              BigDecimal balanceDue, UUID roomTypeId) {
    }

    public record RackBlock(UUID id, LocalDate startDate, LocalDate endDate, BlockType blockType, String reason) {
    }
}
