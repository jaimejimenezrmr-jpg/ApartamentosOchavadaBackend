package com.ochavada.backend.auth;

public enum RevocationReason {
    /** Sustituido por un nuevo token en una rotación normal. */
    ROTATED,
    /** Cierre de sesión explícito. */
    LOGOUT,
    /** Se presentó un token ya rotado: posible robo; se revoca toda la familia. */
    REUSE_DETECTED,
    /** El usuario fue deshabilitado o bloqueado. */
    USER_DISABLED,
    /** Revocado manualmente por un administrador. */
    ADMIN_REVOKED,
    /** El usuario cambió su contraseña. */
    PASSWORD_CHANGED
}
