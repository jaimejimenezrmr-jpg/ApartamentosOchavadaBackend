package com.ochavada.backend.reservation.backoffice.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cambio de fechas u ocupación. Se recalcula el precio con el plan de la reserva salvo que se indique
 * {@code totalAmountOverride}.
 */
public record StayChangeRequest(
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Min(1) @Max(20) Integer adults,
        @NotNull @Min(0) @Max(20) Integer children,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal totalAmountOverride,
        Boolean overrideRestrictions,
        @NotNull Long version) {

    @AssertTrue(message = "La fecha de salida debe ser posterior a la de entrada")
    public boolean isDateRangeValid() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }
}
