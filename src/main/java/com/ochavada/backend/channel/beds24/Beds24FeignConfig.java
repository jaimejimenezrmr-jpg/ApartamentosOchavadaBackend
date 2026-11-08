package com.ochavada.backend.channel.beds24;

import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

/**
 * Configuración exclusiva del cliente Beds24 (sin {@code @Configuration}: no afecta a otros clientes).
 * Añade el access token a todas las llamadas salvo las de autenticación. Sin reintentos automáticos:
 * los reintentos los gobierna el outbox con espera exponencial, que respeta el límite de créditos.
 */
public class Beds24FeignConfig {

    @Bean
    public RequestInterceptor beds24TokenInterceptor(ObjectProvider<Beds24TokenManager> tokenManager) {
        return template -> {
            if (!template.path().contains("/authentication/")) {
                template.header("token", tokenManager.getObject().accessToken());
            }
        };
    }

    @Bean
    public Request.Options beds24RequestOptions(Beds24Properties properties) {
        return new Request.Options(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer beds24Retryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level beds24LoggerLevel() {
        return Logger.Level.NONE;
    }
}
