package com.ochavada.backend.access.web;

import com.ochavada.backend.access.LockAdminService;
import com.ochavada.backend.access.StaffDoorService;
import com.ochavada.backend.access.dto.DoorDtos.UnlockResponse;
import com.ochavada.backend.access.dto.LockDtos.AccessLogEntry;
import com.ochavada.backend.access.dto.LockDtos.LockRequest;
import com.ochavada.backend.access.dto.LockDtos.LockResponse;
import com.ochavada.backend.access.dto.LockDtos.ProviderLock;
import com.ochavada.backend.access.dto.LockDtos.SyncResult;
import com.ochavada.backend.auth.ClientInfo;
import com.ochavada.backend.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cerraduras: configuración (ADMIN, {@code /api/v1/admin/locks}) y operación (recepción,
 * {@code /api/v1/backoffice/locks}).
 */
@RestController
public class LockController {

    private final LockAdminService adminService;
    private final StaffDoorService staffDoorService;

    public LockController(LockAdminService adminService, StaffDoorService staffDoorService) {
        this.adminService = adminService;
        this.staffDoorService = staffDoorService;
    }

    // ------------------------------------------------------------------ administración

    @GetMapping("/api/v1/admin/locks/provider")
    public List<ProviderLock> providerLocks() {
        return adminService.providerLocks();
    }

    @PostMapping("/api/v1/admin/locks")
    public ResponseEntity<LockResponse> create(@Valid @RequestBody LockRequest request) {
        LockResponse created = adminService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/locks/" + created.id())).body(created);
    }

    @PutMapping("/api/v1/admin/locks/{id}")
    public LockResponse update(@PathVariable UUID id, @Valid @RequestBody LockRequest request) {
        return adminService.update(id, request);
    }

    @DeleteMapping("/api/v1/admin/locks/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        adminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/locks/sync")
    public SyncResult sync() {
        return adminService.syncBatteries();
    }

    // ------------------------------------------------------------------ recepción

    @GetMapping("/api/v1/backoffice/locks")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public List<LockResponse> list() {
        return adminService.list();
    }

    @PostMapping("/api/v1/backoffice/locks/{id}/unlock")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public UnlockResponse unlock(@PathVariable UUID id, HttpServletRequest request) {
        return staffDoorService.unlock(id, ClientInfo.from(request));
    }

    @GetMapping("/api/v1/backoffice/locks/{id}/access-log")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public PageResponse<AccessLogEntry> accessLog(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return adminService.accessLog(id, from, to, page, size);
    }

    @GetMapping("/api/v1/backoffice/reservations/{id}/access-log")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public PageResponse<AccessLogEntry> reservationAccessLog(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return adminService.reservationAccessLog(id, page, size);
    }
}
