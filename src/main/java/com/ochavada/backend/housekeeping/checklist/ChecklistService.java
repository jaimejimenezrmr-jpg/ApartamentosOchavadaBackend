package com.ochavada.backend.housekeeping.checklist;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.housekeeping.checklist.ChecklistDtos.ItemRequest;
import com.ochavada.backend.housekeeping.checklist.ChecklistDtos.TemplateRequest;
import com.ochavada.backend.housekeeping.checklist.ChecklistDtos.TemplateResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plantillas de listas de comprobación (administración) y resolución de la plantilla aplicable a una unidad. */
@Service
public class ChecklistService {

    private final ChecklistTemplateRepository repository;
    private final RoomTypeRepository roomTypeRepository;
    private final AuditService auditService;

    public ChecklistService(ChecklistTemplateRepository repository, RoomTypeRepository roomTypeRepository,
                            AuditService auditService) {
        this.repository = repository;
        this.roomTypeRepository = roomTypeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list() {
        return repository.findAllForAdmin().stream().map(TemplateResponse::from).toList();
    }

    @Transactional
    public TemplateResponse create(TemplateRequest request) {
        RoomType roomType = roomType(request.roomTypeId());
        boolean active = request.active() == null || request.active();
        if (active) {
            requireNoOtherActive(roomType, null);
        }
        ChecklistTemplate template = new ChecklistTemplate(roomType, request.name().trim(), active, items(request.items()));
        repository.saveAndFlush(template);
        auditService.record("checklist.created", "checklist_template", template.getId(),
                Map.of("name", template.getName(), "items", template.getItems().size()));
        return TemplateResponse.from(template);
    }

    @Transactional
    public TemplateResponse update(UUID id, TemplateRequest request) {
        ChecklistTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("lista de comprobación", id));
        template.assertVersion(request.version());
        RoomType roomType = roomType(request.roomTypeId());
        boolean active = request.active() == null ? template.isActive() : request.active();
        if (active) {
            requireNoOtherActive(roomType, id);
        }
        template.update(roomType, request.name().trim(), active, items(request.items()));
        repository.flush();
        auditService.record("checklist.updated", "checklist_template", id,
                Map.of("name", template.getName(), "active", active, "items", template.getItems().size()));
        return TemplateResponse.from(template);
    }

    /** Plantilla del tipo de apartamento o, si no tiene, la general. */
    @Transactional(readOnly = true)
    public Optional<ChecklistTemplate> resolveFor(UUID roomTypeId) {
        return repository.findActiveForRoomType(roomTypeId).or(repository::findActiveGeneral);
    }

    private void requireNoOtherActive(RoomType roomType, UUID exceptId) {
        Optional<ChecklistTemplate> other = roomType == null ? repository.findActiveGeneral()
                : repository.findActiveForRoomType(roomType.getId());
        if (other.isPresent() && !other.get().getId().equals(exceptId)) {
            throw new ConflictException("checklist.duplicate_active",
                    roomType == null ? "Ya existe una lista general activa: desactívela o edítela"
                            : "El tipo " + roomType.getName() + " ya tiene una lista activa: desactívela o edítela");
        }
    }

    private RoomType roomType(UUID id) {
        return id == null ? null
                : roomTypeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("tipo de apartamento", id));
    }

    private static List<ChecklistItem> items(List<ItemRequest> requests) {
        List<ChecklistItem> items = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            ItemRequest r = requests.get(i);
            String section = r.section() == null || r.section().isBlank() ? null : r.section().trim();
            items.add(new ChecklistItem(i, section, r.label().trim(), Boolean.TRUE.equals(r.photoRequired())));
        }
        return items;
    }
}
