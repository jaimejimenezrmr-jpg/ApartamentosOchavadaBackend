package com.ochavada.backend.security;

/**
 * Nombres de rol usados en reglas de autorización ({@code hasRole}) y en el claim {@code roles}.
 */
public final class SecurityRoles {

    public static final String ADMIN = "ADMIN";
    public static final String RECEPTIONIST = "RECEPTIONIST";
    public static final String HOUSEKEEPING = "HOUSEKEEPING";
    /** Rol exclusivo de los tokens del portal del huésped. Nunca se asigna a usuarios del backoffice. */
    public static final String GUEST = "GUEST";

    /**
     * Rol provisional de un usuario con contraseña temporal: solo puede consultar su perfil y cambiarla.
     * Sustituye a su rol real hasta que la cambie.
     */
    public static final String PASSWORD_CHANGE_PENDING = "PASSWORD_CHANGE_PENDING";

    public static final String ROLE_PREFIX = "ROLE_";

    private SecurityRoles() {
    }
}
