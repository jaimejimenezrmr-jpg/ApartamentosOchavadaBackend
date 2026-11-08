package com.ochavada.backend.common.error;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param typeBaseUri URI base para el miembro {@code type} de los Problem Details; se le añade el
 *                    {@code code} del problema.
 */
@ConfigurationProperties("app.problem")
public record ProblemProperties(
        @DefaultValue("https://apartamentosochavada.com/problems/") URI typeBaseUri) {
}
