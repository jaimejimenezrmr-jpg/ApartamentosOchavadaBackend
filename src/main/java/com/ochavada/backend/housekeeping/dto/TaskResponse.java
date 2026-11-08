package com.ochavada.backend.housekeeping.dto;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.housekeeping.HousekeepingTask;
import com.ochavada.backend.housekeeping.TaskStatus;
import com.ochavada.backend.housekeeping.TaskType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID roomId,
        String roomNumber,
        HousekeepingStatus roomHousekeepingStatus,
        UUID reservationId,
        TaskType taskType,
        TaskStatus status,
        int priority,
        LocalDate scheduledDate,
        Instant dueBy,
        UUID assignedUserId,
        String notes,
        Instant startedAt,
        Instant completedAt,
        Instant verifiedAt,
        long version) {

    public static TaskResponse from(HousekeepingTask t) {
        return new TaskResponse(t.getId(), t.getRoom().getId(), t.getRoom().getNumber(),
                t.getRoom().getHousekeepingStatus(), t.getReservationId(), t.getTaskType(), t.getStatus(),
                t.getPriority(), t.getScheduledDate(), t.getDueBy(), t.getAssignedUserId(), t.getNotes(),
                t.getStartedAt(), t.getCompletedAt(), t.getVerifiedAt(), t.getVersion());
    }
}
