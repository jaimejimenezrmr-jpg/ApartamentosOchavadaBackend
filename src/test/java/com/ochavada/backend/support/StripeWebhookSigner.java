package com.ochavada.backend.support;

import com.stripe.Stripe;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Genera eventos de Stripe firmados exactamente como lo hace Stripe ({@code t=...,v1=HMAC-SHA256}),
 * para ejercitar la verificación real del SDK en los tests.
 */
public final class StripeWebhookSigner {

    public static final String SECRET = "whsec_test_secret_for_signature_verification";

    private StripeWebhookSigner() {
    }

    public static String paymentIntentEvent(String type, String intentId, long amountCents, String status,
                                            String failureMessage) {
        String lastError = failureMessage == null ? "null"
                : "{\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\",\"message\":\"" + failureMessage + "\",\"type\":\"card_error\"}";
        return """
                {"id":"evt_%s","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":1,"type":"%s",
                 "data":{"object":{"id":"%s","object":"payment_intent","amount":%d,"amount_received":%d,
                 "currency":"eur","status":"%s","latest_charge":%s,"last_payment_error":%s,"metadata":{}}}}
                """.formatted(UUID.randomUUID().toString().replace("-", ""), Stripe.API_VERSION,
                Instant.now().getEpochSecond(), type, intentId, amountCents,
                "succeeded".equals(status) ? amountCents : 0, status,
                "succeeded".equals(status) ? "\"ch_test_" + intentId + "\"" : "null", lastError);
    }

    public static String signature(String payload) {
        return signature(payload, Instant.now().getEpochSecond(), SECRET);
    }

    public static String signature(String payload, long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
