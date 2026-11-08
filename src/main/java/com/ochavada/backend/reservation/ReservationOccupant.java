package com.ochavada.backend.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Viajero asociado a la reserva (tabla {@code reservation_guest}). */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationOccupant {

    @Column(name = "guest_id", nullable = false)
    private UUID guestId;

    @Column(name = "is_holder", nullable = false)
    private boolean holder;

    /** Código de parentesco SES, obligatorio para menores. */
    @Column(name = "relationship", length = 2)
    private String relationship;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ReservationOccupant(UUID guestId, boolean holder, String relationship, Instant createdAt) {
        this.guestId = guestId;
        this.holder = holder;
        this.relationship = relationship;
        this.createdAt = createdAt;
    }
}
