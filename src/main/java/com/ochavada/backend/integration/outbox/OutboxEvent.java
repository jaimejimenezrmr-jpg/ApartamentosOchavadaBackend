package com.ochavada.backend.integration.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Evento pendiente de publicar hacia un sistema externo (patrón Transactional Outbox): se guarda en la
 * misma transacción que el cambio de negocio y un proceso aparte lo entrega con reintentos.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public static final int MAX_ATTEMPTS = 10;
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);
    private static final int MAX_ERROR = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 30)
    private OutboxDestination destination;

    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    OutboxEvent(OutboxDestination destination, String aggregateType, String aggregateId, String eventType,
                String payload, Instant now) {
        this.destination = destination;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.attempts++;
        this.processedAt = now;
        this.lastError = null;
    }

    /** Reintento con espera exponencial (1, 2, 4... minutos, máximo 6 h); tras 10 intentos queda DEAD. */
    void markFailed(String error, Instant now) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR));
        if (attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.DEAD;
            return;
        }
        this.status = OutboxStatus.FAILED;
        Duration backoff = Duration.ofMinutes(1L << Math.min(attempts - 1, 20));
        this.nextAttemptAt = now.plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff);
    }

    public enum OutboxStatus {
        PENDING, PROCESSING, SENT, FAILED, DEAD
    }
}
