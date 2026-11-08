package com.ochavada.backend;

import com.ochavada.backend.support.EmbeddedPostgresSupport;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/**
 * Arranque local sin Docker ni PostgreSQL instalado: levanta el backend con el perfil {@code dev} sobre el
 * PostgreSQL 16 embebido de los tests (los datos se pierden al parar). Pensado para desarrollar el
 * frontend contra la API real:
 *
 * <pre>
 * ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.ochavada.backend.LocalDevServer
 * </pre>
 *
 * Crea un administrador {@code admin@ochavada.local} con la contraseña de {@code DEV_ADMIN_PASSWORD} (o
 * {@code Admin-Local-2026!}). Stripe usa claves de prueba ficticias salvo que se definan {@code STRIPE_*}.
 */
public final class LocalDevServer {

    private LocalDevServer() {
    }

    public static void main(String[] args) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.datasource.url", EmbeddedPostgresSupport.jdbcUrl());
        defaults.put("spring.datasource.username", EmbeddedPostgresSupport.username());
        defaults.put("spring.datasource.password", EmbeddedPostgresSupport.password());
        defaults.put("app.bootstrap.admin.email", "admin@ochavada.local");
        defaults.put("app.bootstrap.admin.password", env("DEV_ADMIN_PASSWORD", "Admin-Local-2026!"));
        defaults.put("app.bootstrap.admin.full-name", "Administración (local)");
        defaults.put("app.stripe.secret-key", env("STRIPE_SECRET_KEY", "sk_test_local_dev_without_stripe"));
        defaults.put("app.stripe.publishable-key", env("STRIPE_PUBLISHABLE_KEY", "pk_test_local_dev_without_stripe"));
        defaults.put("app.stripe.webhook-secret", env("STRIPE_WEBHOOK_SECRET", "whsec_local_dev"));
        defaults.put("app.crypto.credentials-key", env("APP_CREDENTIALS_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
        defaults.put("app.invoicing.issuer-name", "Apartamentos Ochavada (local)");
        defaults.put("app.invoicing.issuer-tax-id", "B00000000");
        defaults.put("app.invoicing.issuer-address", "Calle Pescaderías, 6, 14920 Aguilar de la Frontera (Córdoba)");

        // Propiedades de sistema: tienen precedencia sobre los "${VAR:}" vacíos de application.yml.
        defaults.forEach((key, value) -> {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value.toString());
            }
        });
        SpringApplication app = new SpringApplication(ApartamentosOchavadaApplication.class);
        app.setAdditionalProfiles("dev");
        app.run(args);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
