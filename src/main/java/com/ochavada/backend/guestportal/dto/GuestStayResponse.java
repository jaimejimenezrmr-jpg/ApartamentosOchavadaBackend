package com.ochavada.backend.guestportal.dto;

import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Resumen de la estancia para el portal del huésped. El número de la unidad solo se revela tras el
 * check-in (puede reasignarse hasta entonces).
 */
public record GuestStayResponse(
        String reservationCode,
        ReservationStatus status,
        String holderName,
        String roomTypeName,
        String roomNumber,
        LocalDate checkIn,
        LocalDate checkOut,
        LocalTime checkInTime,
        LocalTime checkOutTime,
        int nights,
        int adults,
        int children,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal pendingAmount,
        String currency,
        PaymentMode paymentMode,
        boolean onlineCheckinCompleted,
        Property property) {

    public record Property(String name, String address, String phone, String email) {
    }
}
