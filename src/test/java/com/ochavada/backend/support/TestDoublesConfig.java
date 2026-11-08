package com.ochavada.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Sustituye las integraciones de red (Stripe, SMTP) por dobles en memoria. Se importa en la clase base
 * para que todos los tests compartan un único contexto de Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfig {

    @Bean
    @Primary
    public FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    @Bean
    @Primary
    public CapturingMailGateway capturingMailGateway() {
        return new CapturingMailGateway();
    }
}
