package com.ochavada.backend.housekeeping;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.dto.RoomResponse;
import com.ochavada.backend.housekeeping.dto.RoomBoardItem;
import com.ochavada.backend.housekeeping.dto.TaskDetailResponse;
import com.ochavada.backend.housekeeping.dto.TaskDetailResponse.CheckResponse;
import com.ochavada.backend.housekeeping.dto.TaskRequest;
import com.ochavada.backend.housekeeping.dto.TaskResponse;
import com.ochavada.backend.media.MediaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Panel de gobernanza. Limpieza consulta, inicia, rellena la lista de comprobación (con fotos) y completa sus
 * tareas; cualquier miembro del personal puede inspeccionar una limpieza ajena; recepción crea, asigna y
 * cancela tareas.
 */
@RestController
@RequestMapping("/api/v1/backoffice/housekeeping")
public class HousekeepingController {

    private final HousekeepingService service;

    public HousekeepingController(HousekeepingService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public List<TaskResponse> board(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID assignedUserId) {
        return service.board(date, status, assignedUserId);
    }

    @GetMapping("/tasks/{id}")
    public TaskDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
        TaskResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/housekeeping/tasks/" + created.id())).body(created);
    }

    @PutMapping("/tasks/{id}/assignee")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public TaskResponse assign(@PathVariable UUID id, @Valid @RequestBody AssigneeRequest request) {
        return service.assign(id, request.userId());
    }

    @PostMapping("/tasks/{id}/start")
    public TaskResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PutMapping("/tasks/{id}/checks/{checkId}")
    public CheckResponse markCheck(@PathVariable UUID id, @PathVariable UUID checkId,
                                   @Valid @RequestBody CheckRequest request) {
        return service.markCheck(id, checkId, request.checked(), request.comment());
    }

    @PostMapping(path = "/tasks/{id}/checks/{checkId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> addCheckPhoto(@PathVariable UUID id, @PathVariable UUID checkId,
                                                       @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).body(service.addCheckPhoto(id, checkId, file));
    }

    @DeleteMapping("/tasks/{id}/checks/{checkId}/photos/{mediaId}")
    public ResponseEntity<Void> deleteCheckPhoto(@PathVariable UUID id, @PathVariable UUID checkId,
                                                 @PathVariable UUID mediaId) {
        service.deleteCheckPhoto(id, checkId, mediaId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{id}/complete")
    public TaskResponse complete(@PathVariable UUID id, @Valid @RequestBody(required = false) CompletionRequest request) {
        return service.complete(id, request == null ? null : request.notes());
    }

    /** Inspección superada: la unidad queda LISTA. */
    @PostMapping("/tasks/{id}/verify")
    public TaskResponse verify(@PathVariable UUID id) {
        return service.verify(id);
    }

    /** Inspección no superada: hay que repetir la limpieza. */
    @PostMapping("/tasks/{id}/reject")
    public TaskResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectionRequest request) {
        return service.reject(id, request.reason());
    }

    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public TaskResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @GetMapping("/rooms")
    public List<RoomBoardItem> rooms() {
        return service.rooms();
    }

    @PutMapping("/rooms/{roomId}/status")
    public RoomResponse changeRoomStatus(@PathVariable UUID roomId, @Valid @RequestBody RoomStatusRequest request) {
        return service.changeRoomStatus(roomId, request.status());
    }

    public record AssigneeRequest(@NotNull UUID userId) {
    }

    public record CompletionRequest(@Size(max = 2000) String notes) {
    }

    public record CheckRequest(@NotNull Boolean checked, @Size(max = 500) String comment) {
    }

    public record RejectionRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RoomStatusRequest(@NotNull HousekeepingStatus status) {
    }
}
