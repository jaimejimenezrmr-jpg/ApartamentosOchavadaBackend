package com.ochavada.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj único de la aplicación (UTC). Inyectarlo en lugar de usar {@code Instant.now()}
 * permite testear de forma determinista las ventanas horarias de estancia y de los tokens.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
