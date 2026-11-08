package com.ochavada.backend.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Fecha "de hoy" según la zona horaria del alojamiento, no la del servidor (que corre en UTC).
 * A las 00:30 en Madrid ya es el día siguiente aunque en UTC aún no lo sea.
 */
@Component
public class PropertyCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public PropertyCalendar(Clock clock, PropertyProperties properties) {
        this.clock = clock;
        this.zone = properties.timeZone();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    public ZoneId zone() {
        return zone;
    }
}
