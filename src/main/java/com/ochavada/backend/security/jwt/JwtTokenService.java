package com.ochavada.backend.security.jwt;

import com.ochavada.backend.security.AppSecurityProperties;
import com.ochavada.backend.security.SecurityRoles;
import com.ochavada.backend.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emisión de access tokens firmados para el personal y para el portal del huésped.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AppSecurityProperties.Jwt properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AppSecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties.jwt();
        this.clock = clock;
    }

    /**
     * Access token de corta duración para un usuario del backoffice.
     */
    public IssuedToken issueStaffAccessToken(UUID userId, String email, String fullName, UserRole role) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Instant now = now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(JwtClaimNames.TOKEN_TYPE, TokenType.STAFF.claimValue())
                .claim(JwtClaimNames.ROLES, List.of(role.name()))
                .claim(JwtClaimNames.EMAIL, email)
                .claim(JwtClaimNames.NAME, fullName)
                .build();
        return encode(claims, now, expiresAt);
    }

    /**
     * Access token del portal del huésped. Su caducidad es la menor entre la vida máxima configurada
     * y {@code notAfter} (normalmente la hora de check-out), de modo que nunca sobrevive a la estancia.
     *
     * @param guestAccessId id del enlace de autoservicio (guest_access_token) que originó la sesión
     * @param reservationId reserva a la que queda limitado el token
     * @param notAfter      instante a partir del cual el token no debe ser válido
     */
    public IssuedToken issueGuestAccessToken(UUID guestAccessId, UUID reservationId, Instant notAfter) {
        Objects.requireNonNull(guestAccessId, "guestAccessId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(notAfter, "notAfter");
        Instant now = now();
        Instant maxExpiry = now.plus(properties.guestTokenMaxTtl());
        Instant expiresAt = notAfter.isBefore(maxExpiry) ? notAfter.truncatedTo(ChronoUnit.SECONDS) : maxExpiry;
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("La ventana de acceso del huésped ya ha finalizado");
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(guestAccessId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(JwtClaimNames.TOKEN_TYPE, TokenType.GUEST.claimValue())
                .claim(JwtClaimNames.ROLES, List.of(SecurityRoles.GUEST))
                .claim(JwtClaimNames.RESERVATION_ID, reservationId.toString())
                .build();
        return encode(claims, now, expiresAt);
    }

    private IssuedToken encode(JwtClaimsSet claims, Instant issuedAt, Instant expiresAt) {
        JwsHeader header = JwsHeader.with(JwtConfig.ALGORITHM).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, issuedAt, expiresAt);
    }

    private Instant now() {
        // Los claims temporales de JWT tienen resolución de segundos.
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
