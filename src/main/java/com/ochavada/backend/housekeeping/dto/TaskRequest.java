package com.ochavada.backend.housekeeping.dto;

import com.ochavada.backend.housekeeping.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskRequest(
        @NotNull UUID roomId,
        UUID reservationId,
        @NotNull TaskType taskType,
        @NotNull LocalDate scheduledDate,
        @Min(1) @Max(5) Integer priority,
        Instant dueBy,
        UUID assignedUserId,
        @Size(max = 2000) String notes) {
}
