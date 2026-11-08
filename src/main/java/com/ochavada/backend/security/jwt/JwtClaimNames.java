package com.ochavada.backend.security.jwt;

/**
 * Claims privados emitidos por la API además de los registrados (iss, sub, aud, exp, iat, nbf, jti).
 */
public final class JwtClaimNames {

    public static final String TOKEN_TYPE = "token_type";
    public static final String ROLES = "roles";
    public static final String EMAIL = "email";
    public static final String NAME = "name";
    public static final String RESERVATION_ID = "reservation_id";

    private JwtClaimNames() {
    }
}
