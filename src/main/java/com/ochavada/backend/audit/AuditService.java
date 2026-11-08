package com.ochavada.backend.audit;

import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.security.CurrentUser;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registro de auditoría. Se escribe en la misma transacción que el cambio auditado: si el cambio se
 * revierte, su rastro también, y nunca queda un cambio sin rastro.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Acción realizada por el usuario autenticado o, en su defecto, por el sistema. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        UUID userId = CurrentUser.staffUserId().orElse(null);
        record(userId != null ? ActorType.STAFF : ActorType.SYSTEM, userId, action, entityType, entityId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ActorType actorType, UUID userId, String action, String entityType, Object entityId,
                       Map<String, ?> details) {
        repository.save(new AuditLog(userId, actorType, action, entityType,
                entityId == null ? null : entityId.toString(),
                details == null || details.isEmpty() ? null : jsonMapper.writeValueAsString(details),
                clock.instant()));
    }
}
