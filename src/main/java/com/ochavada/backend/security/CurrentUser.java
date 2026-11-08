package com.ochavada.backend.security;

import com.ochavada.backend.security.jwt.JwtClaimNames;
import com.ochavada.backend.security.jwt.TokenType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Acceso al usuario autenticado de la petición en curso.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Id del usuario del backoffice si la petición está autenticada con un token de personal. */
    public static Optional<UUID> staffUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && TokenType.STAFF.claimValue().equals(
                        jwtAuthentication.getToken().getClaimAsString(JwtClaimNames.TOKEN_TYPE))) {
            return Optional.of(UUID.fromString(jwtAuthentication.getName()));
        }
        return Optional.empty();
    }
}
