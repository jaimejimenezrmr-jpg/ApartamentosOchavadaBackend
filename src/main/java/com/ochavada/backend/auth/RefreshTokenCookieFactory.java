package com.ochavada.backend.auth;

import com.ochavada.backend.security.AppSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Construye la cookie del refresh token: HttpOnly (inaccesible para JavaScript, mitiga XSS),
 * Secure, SameSite=Strict (mitiga CSRF) y con Path limitado a los endpoints de autenticación.
 */
@Component
public class RefreshTokenCookieFactory {

    private final AppSecurityProperties.RefreshCookie properties;
    private final Clock clock;

    public RefreshTokenCookieFactory(AppSecurityProperties properties, Clock clock) {
        this.properties = properties.refreshCookie();
        this.clock = clock;
    }

    public String cookieName() {
        return properties.name();
    }

    public ResponseCookie create(String value, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        return base(value).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(properties.path());
        if (properties.domain() != null && !properties.domain().isBlank()) {
            builder.domain(properties.domain());
        }
        return builder;
    }
}
