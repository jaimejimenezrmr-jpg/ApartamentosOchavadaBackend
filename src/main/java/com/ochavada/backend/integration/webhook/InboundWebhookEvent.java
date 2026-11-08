package com.ochavada.backend.integration.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Webhook recibido de un proveedor. La unicidad (proveedor, id de evento) garantiza que cada evento se
 * procesa una sola vez aunque el proveedor lo reenvíe.
 */
@Entity
@Table(name = "inbound_webhook_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundWebhookEvent {

    private static final int MAX_ERROR = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private WebhookProvider provider;

    @Column(name = "external_event_id", nullable = false)
    private String externalEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error_message", length = MAX_ERROR)
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public InboundWebhookEvent(WebhookProvider provider, String externalEventId, String eventType, String payload,
                               Instant receivedAt) {
        this.provider = provider;
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = WebhookStatus.RECEIVED;
        this.receivedAt = receivedAt;
    }

    public boolean isDone() {
        return status == WebhookStatus.PROCESSED || status == WebhookStatus.IGNORED;
    }

    public void markProcessed(Instant now) {
        this.status = WebhookStatus.PROCESSED;
        this.attempts++;
        this.processedAt = now;
        this.errorMessage = null;
    }

    public void markIgnored(Instant now) {
        this.status = WebhookStatus.IGNORED;
        this.attempts++;
        this.processedAt = now;
    }

    public void markFailed(String error) {
        this.status = WebhookStatus.FAILED;
        this.attempts++;
        this.errorMessage = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR));
    }

    public enum WebhookProvider {
        STRIPE, BEDS24, TTLOCK
    }

    public enum WebhookStatus {
        RECEIVED, PROCESSED, IGNORED, FAILED
    }
}
