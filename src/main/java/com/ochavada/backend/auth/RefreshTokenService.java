package com.ochavada.backend.auth;

import com.ochavada.backend.security.AppSecurityProperties;
import com.ochavada.backend.user.AppUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida de los refresh tokens: emisión, rotación con detección de reutilización y revocación.
 */
@Slf4j
@Service
public class RefreshTokenService {

    /**
     * Margen durante el cual reutilizar un token recién rotado no se considera robo, sino una carrera
     * legítima (p. ej. dos pestañas refrescando a la vez). Se rechaza sin revocar la familia.
     */
    static final Duration ROTATION_GRACE_PERIOD = Duration.ofSeconds(10);
    private static final int MAX_USER_AGENT = 512;
    private static final int MAX_IP = 45;

    private final RefreshTokenRepository repository;
    private final TokenHasher tokenHasher;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, TokenHasher tokenHasher,
                               AppSecurityProperties properties, Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
        this.refreshTokenTtl = properties.jwt().refreshTokenTtl();
        this.clock = clock;
    }

    /** Emite el primer token de una nueva familia (nuevo inicio de sesión). */
    @Transactional
    public IssuedRefreshToken issueNewSession(AppUser user, ClientInfo client) {
        return issue(user, UUID.randomUUID(), client, clock.instant());
    }

    /**
     * Valida y rota un refresh token: el token presentado queda revocado y se emite otro de la misma
     * familia. Si se presenta un token ya revocado fuera del periodo de gracia, se revoca toda la familia.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotatedSession rotate(String rawToken, ClientInfo client) {
        Instant now = clock.instant();
        RefreshToken current = repository.findForUpdateByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.isRevoked()) {
            handleReuse(current, now);
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }
        AppUser user = current.getUser();
        if (!user.isEnabled() || user.isLockedAt(now)) {
            repository.revokeFamily(current.getFamilyId(), RevocationReason.USER_DISABLED, now);
            throw new InvalidRefreshTokenException();
        }

        IssuedRefreshToken next = issue(user, current.getFamilyId(), client, now);
        current.rotate(next.id(), now);
        return new RotatedSession(user, next);
    }

    /** Cierre de sesión: revoca la familia completa del token presentado. Idempotente. */
    @Transactional
    public void revokeSession(String rawToken) {
        Instant now = clock.instant();
        Optional<RefreshToken> token = repository.findForUpdateByTokenHash(tokenHasher.hash(rawToken));
        token.ifPresent(t -> repository.revokeFamily(t.getFamilyId(), RevocationReason.LOGOUT, now));
    }

    /** Revoca todas las sesiones de un usuario (cambio de contraseña, baja, acción administrativa). */
    @Transactional
    public int revokeAllSessions(UUID userId, RevocationReason reason) {
        return repository.revokeAllForUser(userId, reason, clock.instant());
    }

    private void handleReuse(RefreshToken token, Instant now) {
        boolean recentRotation = token.getRevocationReason() == RevocationReason.ROTATED
                && token.getRevokedAt().plus(ROTATION_GRACE_PERIOD).isAfter(now);
        if (recentRotation) {
            return;
        }
        if (token.getRevocationReason() == RevocationReason.ROTATED) {
            int revoked = repository.revokeFamily(token.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
            log.warn("Reutilización de refresh token detectada para el usuario {} (familia {}); {} tokens revocados",
                    token.getUser().getId(), token.getFamilyId(), revoked);
        }
    }

    private IssuedRefreshToken issue(AppUser user, UUID familyId, ClientInfo client, Instant now) {
        String rawToken = tokenHasher.generateToken();
        Instant expiresAt = now.plus(refreshTokenTtl);
        RefreshToken entity = RefreshToken.issue(user, familyId, tokenHasher.hash(rawToken), now, expiresAt,
                truncate(client.ipAddress(), MAX_IP), truncate(client.userAgent(), MAX_USER_AGENT));
        RefreshToken saved = repository.save(entity);
        return new IssuedRefreshToken(saved.getId(), rawToken, expiresAt);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Valor en claro del token (solo existe en memoria y en la cookie del cliente). */
    public record IssuedRefreshToken(UUID id, String value, Instant expiresAt) {
    }

    public record RotatedSession(AppUser user, IssuedRefreshToken refreshToken) {
    }
}
