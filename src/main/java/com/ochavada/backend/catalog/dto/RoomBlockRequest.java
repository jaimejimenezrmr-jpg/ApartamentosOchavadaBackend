package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.BlockType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bloqueo de una unidad. {@code endDate} es exclusiva: el día en que la unidad vuelve a estar libre.
 * Bloquear solo la noche del 10 de agosto: {@code startDate=2027-08-10, endDate=2027-08-11}.
 */
public record RoomBlockRequest(
        @NotNull UUID roomId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull BlockType blockType,
        @Size(max = 500) String reason) {

    @AssertTrue(message = "La fecha de fin debe ser posterior a la de inicio")
    public boolean isRangeValid() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
