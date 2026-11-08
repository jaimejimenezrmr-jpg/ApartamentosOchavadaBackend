package com.ochavada.backend.security.jwt;

import java.time.Instant;

/**
 * Token firmado listo para devolver al cliente.
 */
public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {

    public long expiresInSeconds() {
        return expiresAt.getEpochSecond() - issuedAt.getEpochSecond();
    }
}
