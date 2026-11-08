package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.AmenityCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AmenityRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{1,49}$",
                message = "El código debe estar en mayúsculas (A-Z, 0-9, _), p. ej. WIFI o AIR_CONDITIONING")
        String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String icon,
        @NotNull AmenityCategory category) {
}
