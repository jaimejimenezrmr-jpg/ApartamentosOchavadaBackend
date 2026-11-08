package com.ochavada.backend.guestportal;

import com.ochavada.backend.reservation.Reservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Enlace de autoservicio del huésped. Solo se guarda el hash SHA-256 del token enviado por email. */
@Entity
@Table(name = "guest_access_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    GuestAccessToken(Reservation reservation, String tokenHash, Instant validFrom, Instant expiresAt, Instant now) {
        this.reservation = reservation;
        this.tokenHash = tokenHash;
        this.validFrom = validFrom;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && !now.isBefore(validFrom) && now.isBefore(expiresAt);
    }

    void registerUse(Instant now) {
        this.useCount++;
        this.lastUsedAt = now;
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
