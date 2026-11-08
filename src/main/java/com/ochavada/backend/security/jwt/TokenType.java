package com.ochavada.backend.security.jwt;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipo de sujeto autenticado por un JWT. Impide que un token de huésped se use en el backoffice
 * y viceversa, aunque ambos estén firmados con la misma clave.
 */
public enum TokenType {

    /** Usuario del backoffice (ADMIN, RECEPTIONIST, HOUSEKEEPING). {@code sub} = id de app_user. */
    STAFF("staff"),
    /** Huésped del portal de autoservicio. {@code sub} = id de guest_access_token. */
    GUEST("guest");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }

    public static Optional<TokenType> fromClaim(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.claimValue.equals(value)).findFirst();
    }
}
