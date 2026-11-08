package com.ochavada.backend.reservation;

public enum ReservationChannel {
    /** Web propia (motor de reservas). */
    DIRECT,
    BOOKING_COM,
    AIRBNB,
    EXPEDIA,
    GOOGLE,
    /** Otros canales conectados a Beds24. */
    BEDS24_OTHER,
    PHONE,
    WALK_IN
}
