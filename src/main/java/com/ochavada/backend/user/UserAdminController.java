package com.ochavada.backend.user;

import com.ochavada.backend.user.dto.UserDtos.CreateUserRequest;
import com.ochavada.backend.user.dto.UserDtos.UpdateUserRequest;
import com.ochavada.backend.user.dto.UserDtos.UserResponse;
import com.ochavada.backend.user.dto.UserDtos.UserWithTemporaryPassword;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Usuarios del backoffice ({@code /api/v1/admin/users}, solo ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<UserWithTemporaryPassword> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(request));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<UserWithTemporaryPassword> resetPassword(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resetPassword(id));
    }

    @PostMapping("/{id}/unlock")
    public UserResponse unlock(@PathVariable UUID id) {
        return service.unlock(id);
    }

    @PostMapping("/{id}/revoke-sessions")
    public UserResponse revokeSessions(@PathVariable UUID id) {
        return service.revokeSessions(id);
    }
}
