package com.ochavada.backend.rates.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Temporada expresada en noches, ambas incluidas: "temporada alta del 1 de julio al 31 de agosto"
 * es {@code firstNight=2027-07-01, lastNight=2027-08-31}.
 */
public record RateSeasonRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull LocalDate firstNight,
        @NotNull LocalDate lastNight,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal nightlyPrice,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal weekendPrice,
        @Min(1) @Max(365) Integer minStay,
        @Min(1) @Max(365) Integer maxStay,
        Long version) {

    @AssertTrue(message = "La última noche no puede ser anterior a la primera")
    public boolean isRangeValid() {
        return firstNight == null || lastNight == null || !lastNight.isBefore(firstNight);
    }

    @AssertTrue(message = "La estancia máxima no puede ser inferior a la mínima")
    public boolean isStayRangeValid() {
        return minStay == null || maxStay == null || maxStay >= minStay;
    }
}
