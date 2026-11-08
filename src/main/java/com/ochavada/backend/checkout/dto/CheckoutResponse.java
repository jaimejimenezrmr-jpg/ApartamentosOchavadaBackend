package com.ochavada.backend.checkout.dto;

import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Resultado del paso 1.
 *
 * @param checkoutToken secreto para consultar el estado y cancelar la retención; la web debe guardarlo
 *                      solo en {@code sessionStorage} y enviarlo en la cabecera {@code X-Checkout-Token}
 * @param payment       instrucciones para Stripe Elements; {@code null} si la tarifa no exige prepago
 *                      (la reserva queda confirmada directamente)
 */
public record CheckoutResponse(
        String reservationCode,
        ReservationStatus status,
        String checkoutToken,
        Instant holdExpiresAt,
        String currency,
        BigDecimal totalAmount,
        BigDecimal prepaymentAmount,
        BigDecimal amountDueAtProperty,
        LocalDate freeCancellationUntil,
        StaySummary stay,
        PaymentInstructions payment) {

    public record StaySummary(
            String roomTypeName,
            String roomTypeSlug,
            String ratePlanName,
            LocalDate checkIn,
            LocalDate checkOut,
            int nights,
            int adults,
            int children) {
    }

    public record PaymentInstructions(String provider, String publishableKey, String clientSecret) {
    }
}
