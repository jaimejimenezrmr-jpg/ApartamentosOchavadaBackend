package com.ochavada.backend.guest;

/** Tipos de documento admitidos por SES.HOSPEDAJES. */
public enum DocumentType {
    /** DNI español. */
    NIF,
    /** Tarjeta de identidad de extranjero. */
    NIE,
    /** Pasaporte. */
    PAS,
    /** Otro documento (p. ej. documento de identidad de un país de la UE). */
    OTRO
}
