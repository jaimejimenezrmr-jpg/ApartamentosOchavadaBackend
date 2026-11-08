package com.ochavada.backend.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Orígenes autorizados para CORS (web pública Angular SSR y backoffice).
 *
 * @param allowedOrigins orígenes exactos, p. ej. {@code https://apartamentosochavada.com}
 * @param maxAge         tiempo de caché de la respuesta preflight
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("1h") Duration maxAge) {
}
