package com.ochavada.backend.auth.dto;

import com.ochavada.backend.auth.AuthService.AuthSession;
import java.time.Instant;

/**
 * Respuesta de login/refresh. El refresh token no aparece en el cuerpo: viaja en una cookie HttpOnly.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        CurrentUserResponse user) {

    public static TokenResponse from(AuthSession session) {
        return new TokenResponse(
                session.accessToken().value(),
                "Bearer",
                session.accessToken().expiresInSeconds(),
                session.accessToken().expiresAt(),
                CurrentUserResponse.from(session.user()));
    }
}
