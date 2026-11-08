package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.RoomService;
import com.ochavada.backend.catalog.dto.RoomRequest;
import com.ochavada.backend.catalog.dto.RoomResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alta, modificación y baja de unidades. Solo ADMIN. La consulta está en {@link BackofficeRoomController}. */
@RestController
@RequestMapping("/api/v1/admin/rooms")
public class AdminRoomController {

    private final RoomService service;

    public AdminRoomController(RoomService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RoomResponse> create(@Valid @RequestBody RoomRequest request) {
        RoomResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/rooms/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public RoomResponse update(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
