package com.ochavada.backend.access.ttlock;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;

/**
 * Configuración exclusiva del cliente TTLock. No lleva {@code @Configuration} a propósito: así no se
 * aplica al resto de clientes Feign de la aplicación.
 * <ul>
 *     <li>Sin reintentos automáticos: una apertura no debe repetirse sin control (se registra cada intento).</li>
 *     <li>Logger NONE: los cuerpos contienen accessToken, clientSecret y contraseña.</li>
 * </ul>
 */
public class TtlockFeignConfig {

    @Bean
    public Request.Options ttlockRequestOptions(TtlockProperties properties) {
        return new Request.Options(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer ttlockRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level ttlockLoggerLevel() {
        return Logger.Level.NONE;
    }
}
