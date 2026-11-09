package com.ochavada.backend.incident;

import com.ochavada.backend.media.MediaResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IncidentDtos {

    private IncidentDtos() {
    }

    /**
     * @param taskId limpieza desde la que se reporta (la unidad se toma de ella)
     * @param origin solo recepción puede indicarlo (STAFF o GUEST); limpieza siempre es HOUSEKEEPING
     */
    public record CreateRequest(
            UUID roomId,
            UUID taskId,
            @NotBlank @Size(max = 150) String title,
            @Size(max = 4000) String description,
            @NotNull IncidentPriority priority,
            IncidentOrigin origin) {
    }

    public record StatusRequest(@NotNull IncidentStatus status, @Size(max = 2000) String notes, @NotNull Long version) {
    }

    public record PriorityRequest(@NotNull IncidentPriority priority, @NotNull Long version) {
    }

    public record IncidentResponse(
            UUID id,
            String code,
            UUID roomId,
            String roomNumber,
            String roomName,
            UUID taskId,
            String title,
            String description,
            IncidentPriority priority,
            IncidentOrigin origin,
            IncidentStatus status,
            UUID reportedByUserId,
            String reportedByName,
            String resolution,
            Instant resolvedAt,
            Instant closedAt,
            Instant createdAt,
            List<MediaResponse> photos,
            long version) {

        static IncidentResponse from(Incident i, String reportedByName, List<MediaResponse> photos) {
            return new IncidentResponse(i.getId(), i.getCode(),
                    i.getRoom() == null ? null : i.getRoom().getId(),
                    i.getRoom() == null ? null : i.getRoom().getNumber(),
                    i.getRoom() == null ? null : i.getRoom().getName(),
                    i.getTaskId(), i.getTitle(), i.getDescription(), i.getPriority(), i.getOrigin(), i.getStatus(),
                    i.getReportedByUserId(), reportedByName, i.getResolution(), i.getResolvedAt(), i.getClosedAt(),
                    i.getCreatedAt(), photos, i.getVersion());
        }
    }
}
