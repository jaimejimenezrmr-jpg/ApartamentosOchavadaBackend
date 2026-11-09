package com.ochavada.backend.lostitem;

import com.ochavada.backend.lostitem.LostItemDtos.CreateRequest;
import com.ochavada.backend.lostitem.LostItemDtos.LostItemResponse;
import com.ochavada.backend.lostitem.LostItemDtos.StatusRequest;
import com.ochavada.backend.lostitem.LostItemDtos.UpdateRequest;
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
 * Objetos olvidados: el personal de limpieza los registra; recepción consulta el listado (con los datos de
 * contacto del huésped), avisa y cierra el expediente.
 */
@RestController
@RequestMapping("/api/v1/backoffice/lost-items")
public class LostItemController {

    private final LostItemService service;

    public LostItemController(LostItemService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public List<LostItemResponse> search(@RequestParam(defaultValue = "true") boolean openOnly) {
        return service.search(openOnly);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public LostItemResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<LostItemResponse> create(@Valid @RequestBody CreateRequest request) {
        LostItemResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/lost-items/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public LostItemResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public LostItemResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
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
