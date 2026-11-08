package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.RoomStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Alta o modificación de una unidad. {@code version} es obligatoria al modificar. */
public record RoomRequest(
        @NotNull UUID roomTypeId,
        @NotBlank @Size(max = 10) @Pattern(regexp = "^[A-Za-z0-9-]+$",
                message = "El número solo admite letras, dígitos y guiones")
        String number,
        @Size(max = 100) String name,
        @Min(-5) @Max(100) Integer floor,
        @NotNull RoomStatus status,
        @Positive Integer beds24UnitId,
        @Size(max = 2000) String notes,
        Long version) {
}
