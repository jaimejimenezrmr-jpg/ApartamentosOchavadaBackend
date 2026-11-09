package com.ochavada.backend.payment.gateway;

import com.ochavada.backend.payment.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Adaptador de Stripe (API de PaymentIntents) con el SDK oficial. Los importes se envían en la unidad
 * mínima de la moneda (céntimos de euro).
 */
@Slf4j
@Component
public class StripePaymentGateway implements PaymentGateway {

    private static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private final StripeClient client;

    public StripePaymentGateway(StripeProperties properties) {
        this.client = new StripeClient(properties.secretKey());
    }

    @Override
    public CreatedIntent createIntent(BigDecimal amount, String currency, String description, String receiptEmail,
                                      Map<String, String> metadata, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setDescription(description)
                .setReceiptEmail(receiptEmail)
                .putAllMetadata(metadata)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .build();
        try {
            PaymentIntent intent = client.v1().paymentIntents().create(params,
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new CreatedIntent(intent.getId(), intent.getClientSecret());
        } catch (StripeException ex) {
            throw failure("No se pudo iniciar el pago", ex);
        }
    }

    @Override
    public IntentSnapshot retrieveIntent(String paymentIntentId) {
        try {
            return snapshot(client.v1().paymentIntents().retrieve(paymentIntentId));
        } catch (StripeException ex) {
            throw failure("No se pudo consultar el pago", ex);
        }
    }

    @Override
    public IntentSnapshot cancelIntent(String paymentIntentId) {
        try {
            return snapshot(client.v1().paymentIntents().cancel(paymentIntentId,
                    PaymentIntentCancelParams.builder()
                            .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                            .build()));
        } catch (InvalidRequestException ex) {
            if (UNEXPECTED_STATE.equals(ex.getCode())) {
                // Ya cobrado, en proceso o cancelado: se informa del estado real.
                return retrieveIntent(paymentIntentId);
            }
            throw failure("No se pudo cancelar el pago", ex);
        } catch (StripeException ex) {
            throw failure("No se pudo cancelar el pago", ex);
        }
    }

    @Override
    public void refund(String paymentIntentId, BigDecimal amount, String reason, String idempotencyKey) {
        try {
            client.v1().refunds().create(RefundCreateParams.builder()
                            .setPaymentIntent(paymentIntentId)
                            .setAmount(toMinorUnits(amount))
                            .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                            .putMetadata("reason", reason.length() > 500 ? reason.substring(0, 500) : reason)
                            .build(),
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
        } catch (StripeException ex) {
            throw failure("No se pudo reembolsar el pago", ex);
        }
    }

    static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static IntentSnapshot snapshot(PaymentIntent intent) {
        return new IntentSnapshot(intent.getId(), IntentStatus.fromStripe(intent.getStatus()), intent.getClientSecret(),
                intent.getLatestCharge());
    }

    private static PaymentGatewayException failure(String message, StripeException ex) {
        log.error("{} (Stripe requestId={}, code={}): {}", message, ex.getRequestId(), ex.getCode(), ex.getMessage());
        return new PaymentGatewayException(message + ". Inténtelo de nuevo en unos minutos", ex);
    }
}
