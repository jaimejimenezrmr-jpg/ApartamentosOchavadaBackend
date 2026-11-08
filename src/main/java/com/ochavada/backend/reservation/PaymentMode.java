package com.ochavada.backend.reservation;

public enum PaymentMode {
    /** Prepago online con Stripe (total o parcial). */
    ONLINE,
    /** Pago íntegro en el alojamiento (planes sin prepago). */
    AT_PROPERTY,
    /** Cobra la OTA (Booking.com, Airbnb...). */
    OTA_COLLECT
}
