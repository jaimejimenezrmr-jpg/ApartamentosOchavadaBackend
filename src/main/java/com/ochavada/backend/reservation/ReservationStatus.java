package com.ochavada.backend.reservation;

public enum ReservationStatus {
    /** Retención temporal del inventario mientras el huésped paga (caduca en {@code hold_expires_at}). */
    PENDING_PAYMENT,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW;

    /** Estados que ocupan inventario (los mismos que la restricción {@code ex_reservation_room_overlap}). */
    public boolean occupiesInventory() {
        return this == PENDING_PAYMENT || this == CONFIRMED || this == CHECKED_IN;
    }
}
