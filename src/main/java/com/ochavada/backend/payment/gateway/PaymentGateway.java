package com.ochavada.backend.payment.gateway;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Puerto hacia la pasarela de pago. La implementación de producción es {@link StripePaymentGateway};
 * los tests usan un doble en memoria. Todas las llamadas son de red: nunca deben hacerse dentro de una
 * transacción de base de datos que mantenga bloqueos.
 */
public interface PaymentGateway {

    /**
     * Crea la intención de cobro que el navegador confirmará con Stripe Elements.
     *
     * @param idempotencyKey misma clave = mismo PaymentIntent (reintentos seguros)
     */
    CreatedIntent createIntent(BigDecimal amount, String currency, String description, String receiptEmail,
                               Map<String, String> metadata, String idempotencyKey);

    /** Estado actual del PaymentIntent y su client secret (para reanudar un checkout idempotente). */
    IntentSnapshot retrieveIntent(String paymentIntentId);

    /**
     * Cancela el PaymentIntent. Si ya está cobrado o en proceso, no se cancela y se devuelve su estado,
     * para que quien llama no libere el inventario de una reserva pagada.
     */
    IntentSnapshot cancelIntent(String paymentIntentId);

    /**
     * Reembolso (total o parcial) de un PaymentIntent cobrado.
     *
     * @param idempotencyKey misma clave = un único reembolso aunque la llamada se repita
     */
    void refund(String paymentIntentId, BigDecimal amount, String reason, String idempotencyKey);

    record CreatedIntent(String paymentIntentId, String clientSecret) {
    }

    record IntentSnapshot(String paymentIntentId, IntentStatus status, String clientSecret) {
    }

    enum IntentStatus {
        REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION, REQUIRES_ACTION, PROCESSING, REQUIRES_CAPTURE,
        SUCCEEDED, CANCELED, UNKNOWN;

        /** El dinero ya está cobrado o comprometido: no se puede liberar la reserva. */
        public boolean isPaidOrInFlight() {
            return this == SUCCEEDED || this == PROCESSING || this == REQUIRES_CAPTURE;
        }

        public static IntentStatus fromStripe(String status) {
            if (status == null) {
                return UNKNOWN;
            }
            return switch (status) {
                case "requires_payment_method" -> REQUIRES_PAYMENT_METHOD;
                case "requires_confirmation" -> REQUIRES_CONFIRMATION;
                case "requires_action" -> REQUIRES_ACTION;
                case "processing" -> PROCESSING;
                case "requires_capture" -> REQUIRES_CAPTURE;
                case "succeeded" -> SUCCEEDED;
                case "canceled" -> CANCELED;
                default -> UNKNOWN;
            };
        }
    }
}
