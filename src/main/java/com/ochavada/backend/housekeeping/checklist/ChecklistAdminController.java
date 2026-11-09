package com.ochavada.backend.housekeeping.checklist;

import com.ochavada.backend.housekeeping.checklist.ChecklistDtos.TemplateRequest;
import com.ochavada.backend.housekeeping.checklist.ChecklistDtos.TemplateResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listas de comprobación de limpieza (solo administración: {@code /api/v1/admin/**}). */
@RestController
@RequestMapping("/api/v1/admin/checklists")
public class ChecklistAdminController {

    private final ChecklistService service;

    public ChecklistAdminController(ChecklistService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<TemplateResponse> create(@Valid @RequestBody TemplateRequest request) {
        TemplateResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/checklists/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return service.update(id, request);
    }
}
