package com.ochavada.backend.support;

import com.ochavada.backend.payment.gateway.PaymentGateway;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pasarela en memoria: imita la semántica de Stripe relevante (idempotencia, cancelación, reembolso). */
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, Intent> intents = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    private final Map<String, Refund> refundsByKey = new ConcurrentHashMap<>();
    private volatile boolean failNextCreate;

    @Override
    public CreatedIntent createIntent(BigDecimal amount, String currency, String description, String receiptEmail,
                                      Map<String, String> metadata, String idempotencyKey) {
        if (failNextCreate) {
            failNextCreate = false;
            throw new com.ochavada.backend.payment.gateway.PaymentGatewayException("Stripe no disponible", null);
        }
        String id = byIdempotencyKey.computeIfAbsent(idempotencyKey, k -> {
            String newId = "pi_test_" + UUID.randomUUID().toString().replace("-", "");
            intents.put(newId, new Intent(newId, amount, newId + "_secret_test", IntentStatus.REQUIRES_PAYMENT_METHOD,
                    receiptEmail, Map.copyOf(metadata)));
            return newId;
        });
        Intent intent = intents.get(id);
        return new CreatedIntent(intent.id(), intent.clientSecret());
    }

    @Override
    public IntentSnapshot retrieveIntent(String paymentIntentId) {
        Intent intent = intents.get(paymentIntentId);
        return new IntentSnapshot(intent.id(), intent.status(), intent.clientSecret());
    }

    @Override
    public IntentSnapshot cancelIntent(String paymentIntentId) {
        Intent intent = intents.get(paymentIntentId);
        if (!intent.status().isPaidOrInFlight() && intent.status() != IntentStatus.CANCELED) {
            intent = intent.withStatus(IntentStatus.CANCELED);
            intents.put(paymentIntentId, intent);
        }
        return new IntentSnapshot(intent.id(), intent.status(), intent.clientSecret());
    }

    @Override
    public void refund(String paymentIntentId, BigDecimal amount, String reason, String idempotencyKey) {
        refundsByKey.computeIfAbsent(idempotencyKey, k -> {
            Refund refund = new Refund(paymentIntentId, amount, reason);
            refunds.add(refund);
            return refund;
        });
    }

    public void setStatus(String paymentIntentId, IntentStatus status) {
        intents.computeIfPresent(paymentIntentId, (id, intent) -> intent.withStatus(status));
    }

    public Intent intent(String paymentIntentId) {
        return intents.get(paymentIntentId);
    }

    /** PaymentIntent cuyo client secret es el indicado (lo que recibe la web). */
    public Intent intentBySecret(String clientSecret) {
        return intents.values().stream().filter(i -> i.clientSecret().equals(clientSecret)).findFirst().orElseThrow();
    }

    public List<Refund> refunds() {
        return refunds;
    }

    public record Refund(String paymentIntentId, BigDecimal amount, String reason) {
    }

    public void failNextCreate() {
        this.failNextCreate = true;
    }

    public record Intent(String id, BigDecimal amount, String clientSecret, IntentStatus status, String receiptEmail,
                         Map<String, String> metadata) {
        Intent withStatus(IntentStatus newStatus) {
            return new Intent(id, amount, clientSecret, newStatus, receiptEmail, metadata);
        }
    }
}
