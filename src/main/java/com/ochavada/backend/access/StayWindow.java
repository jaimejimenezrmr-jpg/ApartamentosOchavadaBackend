package com.ochavada.backend.access;

import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.reservation.Reservation;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Ventana horaria en la que un huésped puede abrir puertas:
 * desde {@code día de llegada + hora de check-in − earlyAccess}
 * hasta {@code día de salida + hora de check-out + lateGrace}, en la zona horaria del alojamiento.
 */
@Component
public class StayWindow {

    private final PropertyProperties property;
    private final AccessProperties access;

    public StayWindow(PropertyProperties property, AccessProperties access) {
        this.property = property;
        this.access = access;
    }

    public Instant opensAt(Reservation r) {
        return r.getCheckInDate().atTime(property.checkInTime()).atZone(property.timeZone()).toInstant()
                .minus(access.earlyAccess());
    }

    public Instant closesAt(Reservation r) {
        return r.getCheckOutDate().atTime(property.checkOutTime()).atZone(property.timeZone()).toInstant()
                .plus(access.lateGrace());
    }

    public boolean contains(Reservation r, Instant now) {
        return !now.isBefore(opensAt(r)) && now.isBefore(closesAt(r));
    }
}
