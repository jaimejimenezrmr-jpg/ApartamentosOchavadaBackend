package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.common.text.Slugs;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Alta o modificación de una tipología. {@code version} es obligatoria al modificar (bloqueo optimista).
 * Si no se indica {@code slug} se genera a partir del nombre.
 */
public record RoomTypeRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,29}$",
                message = "El código debe estar en mayúsculas (A-Z, 0-9, _ o -) y tener entre 2 y 30 caracteres")
        String code,
        @Size(max = Slugs.MAX_LENGTH) @Pattern(regexp = Slugs.PATTERN,
                message = "El slug solo admite minúsculas, dígitos y guiones")
        String slug,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String shortDescription,
        @Size(max = 20_000) String description,
        @NotNull @Min(1) @Max(50) Integer maxOccupancy,
        @NotNull @Min(1) @Max(50) Integer maxAdults,
        @NotNull @Min(0) @Max(50) Integer maxChildren,
        @NotNull @Min(0) @Max(20) Integer bedrooms,
        @NotNull @Min(0) @Max(20) Integer bathrooms,
        @Size(max = 200) String bedsDescription,
        @Positive @Digits(integer = 4, fraction = 2) BigDecimal sizeM2,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull Boolean active,
        @Positive Long beds24RoomId,
        @Size(max = 100) String googleRoomId,
        Long version) {

    @AssertTrue(message = "Los adultos y niños máximos no pueden superar la ocupación máxima")
    public boolean isOccupancyConsistent() {
        if (maxOccupancy == null || maxAdults == null || maxChildren == null) {
            return true;
        }
        return maxAdults <= maxOccupancy && maxChildren <= maxOccupancy;
    }
}
