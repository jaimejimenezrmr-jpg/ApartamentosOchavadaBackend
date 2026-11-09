package com.ochavada.backend.incident;

import com.ochavada.backend.incident.IncidentDtos.CreateRequest;
import com.ochavada.backend.incident.IncidentDtos.IncidentResponse;
import com.ochavada.backend.incident.IncidentDtos.PriorityRequest;
import com.ochavada.backend.incident.IncidentDtos.StatusRequest;
import com.ochavada.backend.media.MediaResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Incidencias. Todo el personal puede reportarlas y consultarlas una a una; el listado, la prioridad y el
 * ciclo de estados son de recepción y administración.
 */
@RestController
@RequestMapping("/api/v1/backoffice/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public List<IncidentResponse> search(@RequestParam(required = false) IncidentStatus status,
                                         @RequestParam(defaultValue = "true") boolean activeOnly,
                                         @RequestParam(required = false) IncidentPriority priority,
                                         @RequestParam(required = false) UUID roomId) {
        return service.search(status, activeOnly, priority, roomId);
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@Valid @RequestBody CreateRequest request) {
        IncidentResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/incidents/" + created.id())).body(created);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public IncidentResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }

    @PutMapping("/{id}/priority")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public IncidentResponse changePriority(@PathVariable UUID id, @Valid @RequestBody PriorityRequest request) {
        return service.changePriority(id, request);
    }

    @PostMapping(path = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> addPhoto(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).body(service.addPhoto(id, file));
    }

    @DeleteMapping("/{id}/photos/{mediaId}")
    public ResponseEntity<Void> deletePhoto(@PathVariable UUID id, @PathVariable UUID mediaId) {
        service.deletePhoto(id, mediaId);
        return ResponseEntity.noContent().build();
    }
}
