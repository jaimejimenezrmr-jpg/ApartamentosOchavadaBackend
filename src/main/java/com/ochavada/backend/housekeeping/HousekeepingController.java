package com.ochavada.backend.housekeeping;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.dto.RoomResponse;
import com.ochavada.backend.housekeeping.dto.TaskRequest;
import com.ochavada.backend.housekeeping.dto.TaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel de gobernanza. Limpieza (HOUSEKEEPING) consulta, inicia y completa tareas; recepción crea,
 * asigna, verifica y cancela.
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

    @PostMapping("/tasks/{id}/complete")
    public TaskResponse complete(@PathVariable UUID id, @Valid @RequestBody(required = false) CompletionRequest request) {
        return service.complete(id, request == null ? null : request.notes());
    }

    @PostMapping("/tasks/{id}/verify")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public TaskResponse verify(@PathVariable UUID id) {
        return service.verify(id);
    }

    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public TaskResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PutMapping("/rooms/{roomId}/status")
    public RoomResponse changeRoomStatus(@PathVariable UUID roomId, @Valid @RequestBody RoomStatusRequest request) {
        return service.changeRoomStatus(roomId, request.status());
    }

    public record AssigneeRequest(@NotNull UUID userId) {
    }

    public record CompletionRequest(@Size(max = 2000) String notes) {
    }

    public record RoomStatusRequest(@NotNull HousekeepingStatus status) {
    }
}
