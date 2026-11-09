package com.ochavada.backend.housekeeping.checklist;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ChecklistDtos {

    private ChecklistDtos() {
    }

    public record ItemRequest(
            @Size(max = 60) String section,
            @NotBlank @Size(max = 200) String label,
            Boolean photoRequired) {
    }

    /**
     * @param roomTypeId tipo de apartamento; nulo para la lista general
     * @param version    obligatoria al modificar
     */
    public record TemplateRequest(
            @NotBlank @Size(max = 120) String name,
            UUID roomTypeId,
            Boolean active,
            @NotNull @Size(min = 1, max = 100) List<@Valid ItemRequest> items,
            Long version) {
    }

    public record ItemResponse(int position, String section, String label, boolean photoRequired) {

        static ItemResponse from(ChecklistItem i) {
            return new ItemResponse(i.getPosition(), i.getSection(), i.getLabel(), i.isPhotoRequired());
        }
    }

    public record TemplateResponse(UUID id, String name, UUID roomTypeId, String roomTypeName, boolean active,
                                   List<ItemResponse> items, long version) {

        public static TemplateResponse from(ChecklistTemplate t) {
            return new TemplateResponse(t.getId(), t.getName(),
                    t.getRoomType() == null ? null : t.getRoomType().getId(),
                    t.getRoomType() == null ? null : t.getRoomType().getName(), t.isActive(),
                    t.getItems().stream().map(ItemResponse::from).toList(), t.getVersion());
        }
    }
}
