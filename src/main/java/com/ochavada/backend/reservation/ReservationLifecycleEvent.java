package com.ochavada.backend.reservation;

import java.util.UUID;

/**
 * Evento de dominio publicado (de forma síncrona, dentro de la transacción del cambio) cuando una reserva
 * cambia de forma relevante para terceros: comunicaciones a SES.HOSPEDAJES, sincronización con el
 * channel manager... Los oyentes escriben en la misma transacción (normalmente en el outbox), de modo
 * que el cambio y sus efectos se confirman o se revierten juntos.
 */
public record ReservationLifecycleEvent(UUID reservationId, Type type) {

    public enum Type {
        CONFIRMED, STAY_CHANGED, CHECKED_IN, CANCELLED,
        /** Datos de los viajeros completados o corregidos (check-in online o recepción). */
        TRAVELERS_UPDATED
    }
}
