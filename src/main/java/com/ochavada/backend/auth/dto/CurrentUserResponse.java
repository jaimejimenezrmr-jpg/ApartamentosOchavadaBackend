package com.ochavada.backend.auth.dto;

import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.UserRole;
import java.time.Instant;
import java.util.UUID;

public record CurrentUserResponse(UUID id, String email, String fullName, UserRole role, Instant lastLoginAt) {

    public static CurrentUserResponse from(AppUser user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getLastLoginAt());
    }
}
