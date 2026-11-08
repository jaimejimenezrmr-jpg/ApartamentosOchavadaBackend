package com.ochavada.backend.reservation.backoffice.dto;

import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationChannel;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReservationSummary(
        UUID id,
        String code,
        ReservationStatus status,
        ReservationChannel channel,
        String holderName,
        String roomTypeName,
        String roomNumber,
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        int adults,
        int children,
        BigDecimal totalAmount,
        BigDecimal balanceDue,
        String currency) {

    public static ReservationSummary from(Reservation r) {
        return new ReservationSummary(r.getId(), r.getCode(), r.getStatus(), r.getChannel(), r.getHolder().fullName(),
                r.getRoomType().getName(), r.getRoom() == null ? null : r.getRoom().getNumber(), r.getCheckInDate(),
                r.getCheckOutDate(), r.nightCount(), r.getAdults(), r.getChildren(), r.getTotalAmount(),
                r.balanceDue(), r.getCurrency());
    }
}
