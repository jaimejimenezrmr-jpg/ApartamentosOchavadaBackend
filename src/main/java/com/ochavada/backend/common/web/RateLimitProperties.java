package com.ochavada.backend.common.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Límites de los endpoints sensibles ({@code app.rate-limit.*}). Complementan (no sustituyen) las reglas
 * de Cloudflare.
 *
 * @param checkout      paso 1 del checkout, por IP
 * @param guestExchange canje de enlaces del huésped, por IP
 * @param guestUnlock   aperturas de puertas, por reserva (protege también el gateway de saturación)
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue @NotNull Limit checkout,
        @DefaultValue @NotNull Limit guestExchange,
        @DefaultValue @NotNull Limit guestUnlock) {

    /**
     * @param requests peticiones admitidas por ventana
     * @param window   duración de la ventana
     */
    public record Limit(
            @Min(1) @DefaultValue("10") int requests,
            @NotNull @DefaultValue("10m") Duration window) {
    }
}
