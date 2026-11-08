package com.ochavada.backend.guestportal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Portal del huésped ({@code app.guest-portal.*}).
 *
 * @param accessUrl          URL de la web que recibe el enlace mágico; el token se añade como fragmento
 *                           ({@code #acceso=...}) para que no viaje a ningún servidor ni quede en logs
 * @param postCheckoutGrace  margen tras la hora de salida durante el que el enlace sigue funcionando
 */
@Validated
@ConfigurationProperties("app.guest-portal")
public record GuestPortalProperties(
        @NotBlank @DefaultValue("https://apartamentosochavada.com/mi-estancia") String accessUrl,
        @NotNull @DefaultValue("2h") Duration postCheckoutGrace) {
}
