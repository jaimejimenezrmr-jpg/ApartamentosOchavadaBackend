package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.AmenityService;
import com.ochavada.backend.catalog.dto.AmenityRequest;
import com.ochavada.backend.catalog.dto.AmenityResponse;
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

/** Servicios/equipamiento del catálogo. Solo ADMIN (ver {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/v1/admin/amenities")
public class AdminAmenityController {

    private final AmenityService service;

    public AdminAmenityController(AmenityService service) {
        this.service = service;
    }

    @GetMapping
    public List<AmenityResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<AmenityResponse> create(@Valid @RequestBody AmenityRequest request) {
        AmenityResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/amenities/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public AmenityResponse update(@PathVariable UUID id, @Valid @RequestBody AmenityRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
