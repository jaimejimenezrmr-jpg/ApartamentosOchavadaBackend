package com.ochavada.backend.catalog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/** Sustituye por completo la lista de servicios de una tipología. */
public record AmenityAssignmentRequest(
        @NotNull @Size(max = 200) Set<@NotNull UUID> amenityIds) {
}
