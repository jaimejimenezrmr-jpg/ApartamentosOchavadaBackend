package com.ochavada.backend.access;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

/**
 * Registro de auditoría de aperturas (tabla de solo inserción). Se registran también los intentos
 * denegados y fallidos: son tan importantes como los exitosos para investigar incidencias.
 */
@Entity
@Immutable
@Table(name = "lock_access_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LockAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lock_device_id")
    private UUID lockDeviceId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "guest_access_id")
    private UUID guestAccessId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10)
    private Result result;

    @Enumerated(EnumType.STRING)
    @Column(name = "denial_reason", length = 40)
    private DenialReason denialReason;

    @Column(name = "provider_error_code")
    private Integer providerErrorCode;

    @Column(name = "provider_message", length = 500)
    private String providerMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @ColumnTransformer(write = "CAST(? AS inet)")
    @Column(name = "client_ip", columnDefinition = "inet")
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Builder
    LockAccessLog(UUID lockDeviceId, UUID reservationId, UUID guestAccessId, UUID userId, ActorType actorType,
                  Action action, Result result, DenialReason denialReason, Integer providerErrorCode,
                  String providerMessage, Integer latencyMs, String clientIp, String userAgent, Instant occurredAt) {
        this.lockDeviceId = lockDeviceId;
        this.reservationId = reservationId;
        this.guestAccessId = guestAccessId;
        this.userId = userId;
        this.actorType = actorType;
        this.action = action;
        this.result = result;
        this.denialReason = denialReason;
        this.providerErrorCode = providerErrorCode;
        this.providerMessage = providerMessage;
        this.latencyMs = latencyMs;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.occurredAt = occurredAt;
    }

    public enum ActorType {
        GUEST, STAFF, SYSTEM
    }

    public enum Action {
        REMOTE_UNLOCK, PASSCODE_CREATE, PASSCODE_DELETE, LOCK_SYNC
    }

    public enum Result {
        SUCCESS, DENIED, FAILED
    }

    public enum DenialReason {
        RESERVATION_NOT_CHECKED_IN, OUTSIDE_STAY_WINDOW, TOKEN_REVOKED, LOCK_NOT_ASSIGNED, LOCK_INACTIVE,
        RATE_LIMITED, NOT_AUTHORIZED
    }
}
