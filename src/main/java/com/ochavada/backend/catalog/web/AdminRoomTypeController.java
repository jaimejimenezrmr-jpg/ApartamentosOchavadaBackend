package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.RoomTypeService;
import com.ochavada.backend.catalog.dto.AmenityAssignmentRequest;
import com.ochavada.backend.catalog.dto.PhotoRequest;
import com.ochavada.backend.catalog.dto.RoomTypeRequest;
import com.ochavada.backend.catalog.dto.RoomTypeResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gestión de tipologías, sus fotos y servicios. Solo ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/room-types")
public class AdminRoomTypeController {

    private final RoomTypeService service;

    public AdminRoomTypeController(RoomTypeService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoomTypeResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public RoomTypeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<RoomTypeResponse> create(@Valid @RequestBody RoomTypeRequest request) {
        RoomTypeResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/room-types/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public RoomTypeResponse update(@PathVariable UUID id, @Valid @RequestBody RoomTypeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/photos")
    public ResponseEntity<RoomTypeResponse> addPhoto(@PathVariable UUID id, @Valid @RequestBody PhotoRequest request) {
        RoomTypeResponse updated = service.addPhoto(id, request);
        return ResponseEntity.created(URI.create("/api/v1/admin/room-types/" + id)).body(updated);
    }

    @PutMapping("/{id}/photos/{photoId}")
    public RoomTypeResponse updatePhoto(@PathVariable UUID id, @PathVariable UUID photoId,
                                        @Valid @RequestBody PhotoRequest request) {
        return service.updatePhoto(id, photoId, request);
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    public RoomTypeResponse removePhoto(@PathVariable UUID id, @PathVariable UUID photoId) {
        return service.removePhoto(id, photoId);
    }

    @PutMapping("/{id}/amenities")
    public RoomTypeResponse replaceAmenities(@PathVariable UUID id,
                                             @Valid @RequestBody AmenityAssignmentRequest request) {
        return service.replaceAmenities(id, request);
    }
}
