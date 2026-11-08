package com.ochavada.backend.user;

/**
 * Roles del personal del backoffice. Jerarquía: ADMIN &gt; RECEPTIONIST &gt; HOUSEKEEPING.
 */
public enum UserRole {
    /** Propietario/gerente: acceso total, usuarios, tarifas, facturación y configuración. */
    ADMIN,
    /** Recepción: reservas, huéspedes, check-in/out, partes SES y apertura remota de cerraduras. */
    RECEPTIONIST,
    /** Gobernanta/limpieza: tareas de limpieza y estado de las unidades. */
    HOUSEKEEPING
}
