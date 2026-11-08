package com.ochavada.backend.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ochavada.backend.security.AppSecurityProperties;
import com.ochavada.backend.user.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class JwtTokenServiceTest {

    private static final String SECRET = "dGVzdC1zZWNyZXQtZm9yLWludGVncmF0aW9uLXRlc3RzLW9ubHktNDgtYnl0ZXMtISE=";
    private static final String ISSUER = "https://test.issuer";
    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AppSecurityProperties properties = properties(SECRET, ISSUER);
    private final JwtConfig config = new JwtConfig(properties);
    private final JwtTokenService service = new JwtTokenService(config.jwtEncoder(), properties, clock);
    private final JwtDecoder decoder = config.jwtDecoder(properties, clock);
    private final RoleClaimJwtAuthenticationConverter converter = new RoleClaimJwtAuthenticationConverter();

    @Test
    void staffTokenContainsExpectedClaimsAndAuthorities() {
        UUID userId = UUID.randomUUID();
        IssuedToken token = service.issueStaffAccessToken(userId, "ana@ochavada.com", "Ana", UserRole.RECEPTIONIST);

        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getClaimAsString(JwtClaimNames.TOKEN_TYPE)).isEqualTo("staff");
        assertThat(jwt.getClaimAsStringList(JwtClaimNames.ROLES)).containsExactly("RECEPTIONIST");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresInSeconds()).isEqualTo(900);

        AbstractAuthenticationToken authentication = converter.convert(jwt);
        assertThat(authentication.getName()).isEqualTo(userId.toString());
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RECEPTIONIST");
    }

    @Test
    void guestTokenExpiryIsCappedAtStayEnd() {
        UUID reservationId = UUID.randomUUID();
        Instant checkout = NOW.plus(Duration.ofHours(2));

        IssuedToken token = service.issueGuestAccessToken(UUID.randomUUID(), reservationId, checkout);

        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getExpiresAt()).isEqualTo(checkout);
        assertThat(jwt.getClaimAsString(JwtClaimNames.RESERVATION_ID)).isEqualTo(reservationId.toString());
        assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_GUEST");
    }

    @Test
    void guestTokenExpiryIsCappedAtMaxTtl() {
        IssuedToken token = service.issueGuestAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                NOW.plus(Duration.ofDays(5)));

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
    }

    @Test
    void guestTokenCannotBeIssuedAfterStayEnded() {
        assertThatThrownBy(() -> service.issueGuestAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        IssuedToken token = service.issueStaffAccessToken(UUID.randomUUID(), "a@b.c", "A", UserRole.ADMIN);
        JwtDecoder later = config.jwtDecoder(properties,
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.decode(token.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        AppSecurityProperties other = properties(SECRET, "https://evil.example");
        JwtConfig otherConfig = new JwtConfig(other);
        String foreign = new JwtTokenService(otherConfig.jwtEncoder(), other, clock)
                .issueStaffAccessToken(UUID.randomUUID(), "a@b.c", "A", UserRole.ADMIN).value();

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        AppSecurityProperties other = properties(
                "b3RoZXItc2VjcmV0LWtleS10aGF0LWlzLWRlZmluaXRlbHktbG9uZy1lbm91Z2gtISE=", ISSUER);
        JwtConfig otherConfig = new JwtConfig(other);
        String forged = new JwtTokenService(otherConfig.jwtEncoder(), other, clock)
                .issueStaffAccessToken(UUID.randomUUID(), "a@b.c", "A", UserRole.ADMIN).value();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void staffTokenWithGuestRoleIsRejectedByConverter() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim(JwtClaimNames.TOKEN_TYPE, "staff")
                .claim(JwtClaimNames.ROLES, List.of("GUEST"))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void guestTokenWithoutReservationIsRejectedByConverter() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim(JwtClaimNames.TOKEN_TYPE, "guest")
                .claim(JwtClaimNames.ROLES, List.of("GUEST"))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtConfig(properties("c2hvcnQ=", ISSUER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    private static AppSecurityProperties properties(String secret, String issuer) {
        return new AppSecurityProperties(
                new AppSecurityProperties.Jwt(issuer, secret, Duration.ofMinutes(15), Duration.ofDays(14),
                        Duration.ofHours(12)),
                new AppSecurityProperties.RefreshCookie("ochavada_rt", "/api/v1/auth", true, "Strict", null),
                new AppSecurityProperties.Login(5, Duration.ofMinutes(15)));
    }
}
