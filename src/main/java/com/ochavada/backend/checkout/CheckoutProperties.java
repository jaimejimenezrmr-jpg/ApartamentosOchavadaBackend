package com.ochavada.backend.checkout;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param holdDuration          tiempo que se retiene la unidad mientras el huésped paga
 * @param inFlightHoldExtension ampliación de la retención si al caducar el pago está en curso
 */
@Validated
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(
        @NotNull @DefaultValue("15m") Duration holdDuration,
        @NotNull @DefaultValue("30m") Duration inFlightHoldExtension) {
}
