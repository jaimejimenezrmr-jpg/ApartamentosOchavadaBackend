package com.ochavada.backend.incident;

/** Prioridades del centro de incidencias. */
public enum IncidentPriority {
    /** Emergencia: fuga importante, seguridad, huésped sin poder entrar, fallo eléctrico grave. Actuación inmediata. */
    P1,
    /** Afecta a la estancia: sin climatización, sin agua caliente, cerradura problemática. Atención prioritaria. */
    P2,
    /** Normal: bombilla, mando, pequeño desperfecto. Se puede programar. */
    P3,
    /** Preventivo o mejora: pintura, ajustes, desgaste. */
    P4
}
