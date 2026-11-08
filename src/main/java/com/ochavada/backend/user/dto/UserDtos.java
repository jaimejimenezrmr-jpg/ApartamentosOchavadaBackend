package com.ochavada.backend.user.dto;

import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Gestión de usuarios del backoffice. */
public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            UserRole role,
            boolean enabled,
            boolean locked,
            Instant lockedUntil,
            boolean passwordChangeRequired,
            Instant lastLoginAt,
            Instant passwordChangedAt,
            Instant createdAt,
            long version) {

        public static UserResponse from(AppUser u, Instant now) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(), u.isEnabled(),
                    u.isLockedAt(now), u.isLockedAt(now) ? u.getLockedUntil() : null, u.isPasswordChangeRequired(),
                    u.getLastLoginAt(), u.getPasswordChangedAt(), u.getCreatedAt(), u.getVersion());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido") String phone,
            @NotNull UserRole role) {

        public CreateUserRequest {
            email = email == null ? null : email.strip();
        }
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido") String phone,
            @NotNull UserRole role,
            @NotNull Boolean enabled,
            @NotNull Long version) {
    }

    /** La contraseña temporal solo se devuelve en esta respuesta: no se guarda en claro en ningún sitio. */
    public record UserWithTemporaryPassword(UserResponse user, String temporaryPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(max = 200) String newPassword) {
    }
}
