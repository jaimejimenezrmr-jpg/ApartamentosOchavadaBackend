package com.ochavada.backend.payment;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Credenciales de Stripe ({@code app.stripe.*}). En desarrollo usar claves {@code sk_test_}/{@code pk_test_}.
 *
 * @param secretKey               clave secreta (solo servidor)
 * @param publishableKey          clave pública que se entrega a Stripe Elements
 * @param webhookSecret           secreto de firma del endpoint de webhooks ({@code whsec_...})
 * @param webhookTolerance        antigüedad máxima aceptada de un evento firmado (anti-replay)
 */
@Validated
@ConfigurationProperties("app.stripe")
public record StripeProperties(
        @NotBlank String secretKey,
        @NotBlank String publishableKey,
        @NotBlank String webhookSecret,
        @DefaultValue("5m") Duration webhookTolerance) {
}
