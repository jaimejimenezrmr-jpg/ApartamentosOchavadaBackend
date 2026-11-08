package com.ochavada.backend.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Foto de una tipología. El texto alternativo es obligatorio por accesibilidad y SEO.
 */
public record PhotoRequest(
        @NotBlank @Size(max = 1024) @Pattern(regexp = "^https://\\S+$", message = "La URL debe ser https")
        String url,
        @NotBlank @Size(max = 250) String altText,
        @NotNull @Min(0) Integer displayOrder,
        @NotNull Boolean cover) {
}
