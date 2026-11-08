package com.ochavada.backend.access.ttlock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cuenta de TTLock Open Platform ({@code app.ttlock.*}).
 *
 * @param enabled        {@code false} desactiva las aperturas remotas (p. ej. antes de instalar las cerraduras)
 * @param baseUrl        {@code https://euapi.ttlock.com} (cuentas europeas) o {@code https://api.ttlock.com}
 * @param clientId       client_id de la aplicación registrada en el portal de desarrolladores
 * @param clientSecret   client_secret de la aplicación
 * @param username       usuario de la app TTLock propietario de las cerraduras
 * @param password       contraseña de ese usuario (la API exige enviarla como MD5; se calcula al vuelo)
 * @param connectTimeout tiempo máximo de conexión con la API
 * @param readTimeout    tiempo máximo de respuesta: una apertura vía gateway puede tardar varios segundos
 * @param tokenRefreshMargin antelación con la que se renueva el access token antes de caducar
 */
@Validated
@ConfigurationProperties("app.ttlock")
public record TtlockProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("https://euapi.ttlock.com") String baseUrl,
        String clientId,
        String clientSecret,
        String username,
        String password,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("20s") Duration readTimeout,
        @NotNull @DefaultValue("1d") Duration tokenRefreshMargin) {

    public boolean isConfigured() {
        return enabled && notBlank(clientId) && notBlank(clientSecret) && notBlank(username) && notBlank(password);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
