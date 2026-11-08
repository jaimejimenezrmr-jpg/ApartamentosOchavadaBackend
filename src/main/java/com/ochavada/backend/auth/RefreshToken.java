package com.ochavada.backend.auth;

import com.ochavada.backend.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Refresh token opaco. Solo se persiste el hash SHA-256 del valor entregado al cliente.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 30)
    private RevocationReason revocationReason;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "created_ip", length = 45)
    private String createdIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    public static RefreshToken issue(AppUser user, UUID familyId, String tokenHash, Instant issuedAt,
                                     Instant expiresAt, String createdIp, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.user = Objects.requireNonNull(user, "user");
        token.familyId = Objects.requireNonNull(familyId, "familyId");
        token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        token.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        token.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        token.createdIp = createdIp;
        token.userAgent = userAgent;
        return token;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void rotate(UUID replacementId, Instant now) {
        this.replacedById = Objects.requireNonNull(replacementId, "replacementId");
        revoke(RevocationReason.ROTATED, now);
    }

    public void revoke(RevocationReason reason, Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revocationReason = reason;
        }
    }
}
