package com.ochavada.backend.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Precio de una noche de la reserva (tabla {@code reservation_night}). */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NightCharge {

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "rate_plan_id")
    private UUID ratePlanId;

    public NightCharge(LocalDate stayDate, BigDecimal price, UUID ratePlanId) {
        this.stayDate = stayDate;
        this.price = price;
        this.ratePlanId = ratePlanId;
    }
}
