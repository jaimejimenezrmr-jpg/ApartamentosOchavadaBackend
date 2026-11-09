package com.ochavada.backend.operations;

import com.ochavada.backend.config.PropertyCalendar;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Códigos legibles de expedientes operativos: {@code INC-2026-0001}, {@code OBJ-2026-0001}. Numeración
 * correlativa por tipo y año (hora del establecimiento), sin huecos salvo que la transacción se deshaga.
 */
@Component
public class OpsCodeGenerator {

    public enum Kind {
        INC, OBJ
    }

    private final JdbcClient jdbc;
    private final PropertyCalendar calendar;

    public OpsCodeGenerator(JdbcClient jdbc, PropertyCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    /** Debe llamarse dentro de la transacción que crea el expediente: el contador queda bloqueado hasta su fin. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Kind kind) {
        int year = calendar.today().getYear();
        int value = jdbc.sql("""
                        INSERT INTO ops_counter (kind, year, last_value) VALUES (:kind, :year, 1)
                        ON CONFLICT (kind, year) DO UPDATE SET last_value = ops_counter.last_value + 1
                        RETURNING last_value
                        """)
                .param("kind", kind.name()).param("year", year)
                .query(Integer.class).single();
        return "%s-%d-%04d".formatted(kind.name(), year, value);
    }
}
