package com.ochavada.backend.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Instancia única de PostgreSQL 16 real (binarios embebidos) compartida por todos los tests de
 * integración de la JVM. Permite validar la migración Flyway, restricciones EXCLUDE, triggers y
 * el mapeo JPA sin depender de Docker.
 */
public final class EmbeddedPostgresSupport {

    private static final EmbeddedPostgres POSTGRES = start();

    private EmbeddedPostgresSupport() {
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl("postgres", "postgres");
    }

    public static String username() {
        return "postgres";
    }

    public static String password() {
        return "postgres";
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    .setServerConfig("timezone", "UTC")
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // La JVM está terminando.
                }
            }, "embedded-postgres-shutdown"));
            return postgres;
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo arrancar PostgreSQL embebido", ex);
        }
    }
}
