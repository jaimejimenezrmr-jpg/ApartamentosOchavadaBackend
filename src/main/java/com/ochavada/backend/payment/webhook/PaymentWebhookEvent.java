package com.ochavada.backend.payment.webhook;

import java.math.BigDecimal;

/**
 * Evento de Stripe ya verificado y reducido a lo que necesita el dominio.
 *
 * @param paymentIntentId nulo si el evento no se refiere a un PaymentIntent
 * @param amountReceived  importe cobrado (unidad monetaria, no céntimos)
 */
public record PaymentWebhookEvent(
        String eventId,
        String type,
        String paymentIntentId,
        BigDecimal amountReceived,
        String chargeId,
        String failureCode,
        String failureMessage,
        String rawPayload) {
}
