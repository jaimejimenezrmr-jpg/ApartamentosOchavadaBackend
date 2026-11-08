package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.RoomBlockService;
import com.ochavada.backend.catalog.dto.RoomBlockRequest;
import com.ochavada.backend.catalog.dto.RoomBlockResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bloqueos de unidades. Recepción y administración. */
@RestController
@RequestMapping("/api/v1/backoffice/room-blocks")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class RoomBlockController {

    private final RoomBlockService service;

    public RoomBlockController(RoomBlockService service) {
        this.service = service;
    }

    /** Bloqueos que se solapan con {@code [from, to)}. */
    @GetMapping
    public List<RoomBlockResponse> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID roomId) {
        return service.list(from, to, roomId);
    }

    @PostMapping
    public ResponseEntity<RoomBlockResponse> create(@Valid @RequestBody RoomBlockRequest request) {
        RoomBlockResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/room-blocks/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
