package com.ochavada.backend.channel;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Marcas de sincronización persistentes (tabla {@code integration_sync_state}): permiten que el sondeo
 * incremental continúe donde lo dejó tras un reinicio o desde otra instancia.
 */
@Repository
public class SyncStateRepository {

    private final JdbcClient jdbc;

    public SyncStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> lastSyncedAt(String name) {
        return jdbc.sql("SELECT last_synced_at FROM integration_sync_state WHERE name = :name")
                .param("name", name)
                .query((rs, rowNum) -> rs.getTimestamp("last_synced_at").toInstant())
                .optional();
    }

    /**
     * Bloquea la marca (creándola si no existe) para que solo una instancia sondee a la vez; debe
     * llamarse dentro de una transacción. Devuelve {@code false} si otra instancia la tiene bloqueada.
     */
    public boolean tryLock(String name, Instant initialValue) {
        jdbc.sql("""
                        INSERT INTO integration_sync_state (name, last_synced_at) VALUES (:name, :initial)
                        ON CONFLICT (name) DO NOTHING
                        """)
                .param("name", name)
                .param("initial", java.sql.Timestamp.from(initialValue))
                .update();
        return jdbc.sql("SELECT name FROM integration_sync_state WHERE name = :name FOR UPDATE SKIP LOCKED")
                .param("name", name)
                .query(String.class)
                .optional()
                .isPresent();
    }

    public void update(String name, Instant value) {
        jdbc.sql("UPDATE integration_sync_state SET last_synced_at = :value, updated_at = now() WHERE name = :name")
                .param("name", name)
                .param("value", java.sql.Timestamp.from(value))
                .update();
    }
}
