package com.ochavada.backend.rates.dto;

import com.ochavada.backend.rates.MealPlan;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Alta o modificación de un plan de tarifa. Si es reembolsable, {@code freeCancellationDays} indica
 * hasta cuántos días antes de la llegada se puede cancelar sin coste. {@code version} es obligatoria al modificar.
 */
public record RatePlanRequest(
        @NotNull UUID roomTypeId,
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,29}$",
                message = "El código debe estar en mayúsculas (A-Z, 0-9, _ o -), p. ej. FLEX o NR")
        String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotNull MealPlan mealPlan,
        @NotNull Boolean refundable,
        @Min(0) @Max(365) Integer freeCancellationDays,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
        BigDecimal prepaymentPercent,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
        @NotNull @Min(1) @Max(365) Integer minStay,
        @Min(1) @Max(365) Integer maxStay,
        @NotNull Boolean defaultPlan,
        @NotNull Boolean active,
        @Size(max = 50) String channelRateCode,
        Long version) {

    @AssertTrue(message = "Una tarifa reembolsable debe indicar los días de cancelación gratuita")
    public boolean isCancellationPolicyComplete() {
        return refundable == null || !refundable || freeCancellationDays != null;
    }

    @AssertTrue(message = "La estancia máxima no puede ser inferior a la mínima")
    public boolean isStayRangeValid() {
        return minStay == null || maxStay == null || maxStay >= minStay;
    }
}
