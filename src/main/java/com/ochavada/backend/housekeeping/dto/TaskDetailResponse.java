package com.ochavada.backend.housekeeping.dto;

import com.ochavada.backend.housekeeping.HousekeepingTaskCheck;
import com.ochavada.backend.incident.IncidentDtos.IncidentResponse;
import com.ochavada.backend.lostitem.LostItemDtos.LostItemResponse;
import com.ochavada.backend.media.MediaResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ficha de una tarea: lista de comprobación con sus fotos y lo reportado durante la limpieza. */
public record TaskDetailResponse(
        TaskResponse task,
        List<CheckResponse> checks,
        List<IncidentResponse> incidents,
        List<LostItemResponse> lostItems) {

    public record CheckResponse(
            UUID id,
            int position,
            String section,
            String label,
            boolean photoRequired,
            boolean checked,
            Instant checkedAt,
            UUID checkedByUserId,
            String comment,
            List<MediaResponse> photos) {

        public static CheckResponse from(HousekeepingTaskCheck c, List<MediaResponse> photos) {
            return new CheckResponse(c.getId(), c.getPosition(), c.getSection(), c.getLabel(), c.isPhotoRequired(),
                    c.isChecked(), c.getCheckedAt(), c.getCheckedByUserId(), c.getComment(), photos);
        }
    }
}
