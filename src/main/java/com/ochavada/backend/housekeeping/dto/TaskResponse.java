package com.ochavada.backend.housekeeping.dto;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.housekeeping.HousekeepingTask;
import com.ochavada.backend.housekeeping.HousekeepingTaskCheck;
import com.ochavada.backend.housekeeping.TaskStatus;
import com.ochavada.backend.housekeeping.TaskType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Tarea de gobernanza para tableros y listados.
 *
 * @param checksTotal     puntos de la lista de comprobación (0 si no tiene o aún no se ha empezado)
 * @param checksDone      puntos marcados
 * @param photosMissing   puntos con foto obligatoria que aún no la tienen
 * @param durationMinutes duración de la última ejecución (de empezar a terminar)
 */
public record TaskResponse(
        UUID id,
        UUID roomId,
        String roomNumber,
        String roomName,
        HousekeepingStatus roomHousekeepingStatus,
        UUID reservationId,
        TaskType taskType,
        TaskStatus status,
        int priority,
        LocalDate scheduledDate,
        Instant dueBy,
        UUID assignedUserId,
        String assignedUserName,
        String notes,
        Instant startedAt,
        Instant completedAt,
        UUID completedByUserId,
        Instant verifiedAt,
        UUID verifiedByUserId,
        int rejectionCount,
        String rejectionReason,
        int checksTotal,
        int checksDone,
        int photosMissing,
        Long durationMinutes,
        long version) {

    /** Sin información de fotos ni nombres (p. ej. respuestas a acciones que no la necesitan). */
    public static TaskResponse from(HousekeepingTask t) {
        return from(t, Map.of(), Map.of());
    }

    /**
     * @param photoCounts fotos por punto de la lista
     * @param userNames   nombres del personal por id
     */
    public static TaskResponse from(HousekeepingTask t, Map<UUID, Long> photoCounts, Map<UUID, String> userNames) {
        int done = 0;
        int missing = 0;
        for (HousekeepingTaskCheck c : t.getChecks()) {
            if (c.isChecked()) {
                done++;
            }
            if (c.isPhotoRequired() && photoCounts.getOrDefault(c.getId(), 0L) == 0) {
                missing++;
            }
        }
        Long duration = t.getStartedAt() != null && t.getCompletedAt() != null
                ? Duration.between(t.getStartedAt(), t.getCompletedAt()).toMinutes() : null;
        return new TaskResponse(t.getId(), t.getRoom().getId(), t.getRoom().getNumber(), t.getRoom().getName(),
                t.getRoom().getHousekeepingStatus(), t.getReservationId(), t.getTaskType(), t.getStatus(),
                t.getPriority(), t.getScheduledDate(), t.getDueBy(), t.getAssignedUserId(),
                t.getAssignedUserId() == null ? null : userNames.get(t.getAssignedUserId()), t.getNotes(),
                t.getStartedAt(), t.getCompletedAt(), t.getCompletedByUserId(), t.getVerifiedAt(), t.getVerifiedByUserId(), t.getRejectionCount(),
                t.getRejectionReason(), t.getChecks().size(), done, missing, duration, t.getVersion());
    }
}
