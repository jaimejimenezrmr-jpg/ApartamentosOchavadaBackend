package com.ochavada.backend.payment.webhook;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.payment.StripeProperties;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import java.math.BigDecimal;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verifica la firma {@code Stripe-Signature} (HMAC-SHA256 con el secreto del endpoint y control de
 * antigüedad) y extrae los datos del PaymentIntent. Sin red: se usa tal cual en los tests.
 */
@Slf4j
@Component
public class StripeWebhookParser {

    private final StripeProperties properties;
    private final Clock clock;

    public StripeWebhookParser(StripeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public PaymentWebhookEvent parse(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw invalidSignature();
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, properties.webhookSecret(),
                    properties.webhookTolerance().toSeconds(), clock);
        } catch (SignatureVerificationException ex) {
            log.warn("Webhook de Stripe con firma no válida: {}", ex.getMessage());
            throw invalidSignature();
        } catch (RuntimeException ex) {
            // JSON mal formado
            throw new BadRequestException("webhook.invalid_payload", "El cuerpo del webhook no es válido");
        }

        StripeObject object = dataObject(event);
        if (object instanceof PaymentIntent intent) {
            StripeError lastError = intent.getLastPaymentError();
            return new PaymentWebhookEvent(event.getId(), event.getType(), intent.getId(),
                    intent.getAmountReceived() == null ? null : BigDecimal.valueOf(intent.getAmountReceived(), 2),
                    intent.getLatestCharge(),
                    lastError != null ? firstNonNull(lastError.getDeclineCode(), lastError.getCode()) : null,
                    lastError != null ? lastError.getMessage() : null,
                    payload);
        }
        return new PaymentWebhookEvent(event.getId(), event.getType(), null, null, null, null, null, payload);
    }

    private static StripeObject dataObject(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            return deserializer.getObject().get();
        }
        // Versión de API del evento distinta de la del SDK: se intenta igualmente.
        try {
            return deserializer.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException ex) {
            log.warn("No se pudo deserializar el objeto del evento {} ({})", event.getId(), event.getType());
            return null;
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private static BadRequestException invalidSignature() {
        return new BadRequestException("webhook.invalid_signature", "Firma del webhook no válida");
    }
}
