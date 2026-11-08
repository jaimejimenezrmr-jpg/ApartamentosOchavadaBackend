package com.ochavada.backend.checkout.dto;

import com.ochavada.backend.payment.PaymentStatus;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Estado del checkout para la página de espera tras confirmar el pago en Stripe Elements: la web
 * consulta hasta que {@code status} pasa a {@code CONFIRMED} (webhook recibido) o la retención caduca.
 *
 * @param paymentFailureMessage motivo del último rechazo, para mostrarlo y permitir reintentar
 */
public record CheckoutStatusResponse(
        String reservationCode,
        ReservationStatus status,
        PaymentStatus paymentStatus,
        String paymentFailureMessage,
        Instant holdExpiresAt,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        String currency) {
}
