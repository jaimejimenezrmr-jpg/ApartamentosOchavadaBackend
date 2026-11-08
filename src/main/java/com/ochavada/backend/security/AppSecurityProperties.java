package com.ochavada.backend.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de seguridad de la aplicación ({@code app.security.*}).
 */
@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull RefreshCookie refreshCookie,
        @Valid @NotNull Login login) {

    /**
     * @param issuer          valor del claim {@code iss} emitido y exigido
     * @param secret          clave HMAC-SHA256 en Base64 (mínimo 256 bits)
     * @param accessTokenTtl  vida del access token del personal
     * @param refreshTokenTtl vida del refresh token del personal
     * @param guestTokenMaxTtl vida máxima de un token de huésped (se recorta además al fin de la estancia)
     */
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String secret,
            @NotNull @DefaultValue("15m") Duration accessTokenTtl,
            @NotNull @DefaultValue("14d") Duration refreshTokenTtl,
            @NotNull @DefaultValue("12h") Duration guestTokenMaxTtl) {
    }

    /**
     * Cookie HttpOnly que transporta el refresh token (nunca accesible desde JavaScript).
     */
    public record RefreshCookie(
            @NotBlank @DefaultValue("ochavada_rt") String name,
            @NotBlank @DefaultValue("/api/v1/auth") String path,
            @DefaultValue("true") boolean secure,
            @NotBlank @Pattern(regexp = "Strict|Lax|None") @DefaultValue("Strict") String sameSite,
            String domain) {
    }

    /**
     * Protección contra fuerza bruta en el login.
     */
    public record Login(
            @Min(1) @DefaultValue("5") int maxFailedAttempts,
            @NotNull @DefaultValue("15m") Duration lockDuration) {
    }
}
