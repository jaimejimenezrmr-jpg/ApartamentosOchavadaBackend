package com.ochavada.backend.incident;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de una incidencia: OPEN → IN_PROGRESS → RESOLVED → CLOSED (o CANCELLED mientras está abierta). Una
 * resuelta puede reabrirse (vuelve a IN_PROGRESS) si la revisión no la da por buena.
 */
public enum IncidentStatus {
    OPEN, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED;

    public static final Set<IncidentStatus> ACTIVE = EnumSet.of(OPEN, IN_PROGRESS);

    public boolean canMoveTo(IncidentStatus target) {
        return switch (this) {
            case OPEN -> target == IN_PROGRESS || target == RESOLVED || target == CANCELLED;
            case IN_PROGRESS -> target == RESOLVED || target == CANCELLED;
            case RESOLVED -> target == CLOSED || target == IN_PROGRESS;
            case CLOSED, CANCELLED -> false;
        };
    }
}
