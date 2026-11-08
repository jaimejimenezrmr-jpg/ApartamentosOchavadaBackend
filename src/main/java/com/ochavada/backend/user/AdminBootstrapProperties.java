package com.ochavada.backend.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Credenciales del primer administrador. Solo se usan si aún no existe ningún ADMIN; tras el primer
 * arranque deben eliminarse del entorno.
 */
@ConfigurationProperties("app.bootstrap.admin")
public record AdminBootstrapProperties(
        String email,
        String password,
        @DefaultValue("Administrador") String fullName) {

    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
