package com.ochavada.backend.access;

import com.ochavada.backend.access.LockAccessLog.Action;
import com.ochavada.backend.access.LockAccessLog.ActorType;
import com.ochavada.backend.access.LockAccessLog.Result;
import com.ochavada.backend.access.dto.DoorDtos.UnlockResponse;
import com.ochavada.backend.access.ttlock.TtlockException;
import com.ochavada.backend.access.ttlock.TtlockGateway;
import com.ochavada.backend.access.ttlock.TtlockGateway.UnlockResult;
import com.ochavada.backend.auth.ClientInfo;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Apertura remota por el personal (huésped sin batería en el móvil, entrega a mantenimiento...). Queda
 * registrada con el usuario que la ordena.
 */
@Service
public class StaffDoorService {

    private final LockDeviceRepository repository;
    private final TtlockGateway gateway;
    private final LockAccessLogger accessLogger;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public StaffDoorService(LockDeviceRepository repository, TtlockGateway gateway, LockAccessLogger accessLogger,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.accessLogger = accessLogger;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    public UnlockResponse unlock(UUID lockId, ClientInfo client) {
        UUID userId = CurrentUser.staffUserId()
                .orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        LockDevice lock = Objects.requireNonNull(readTx.execute(s ->
                repository.findById(lockId).orElseThrow(() -> new ResourceNotFoundException("cerradura", lockId))));
        if (!lock.isActive()) {
            throw new ConflictException("access.lock_inactive", "La cerradura " + lock.getName() + " está desactivada");
        }
        Instant now = clock.instant();
        UnlockResult result = gateway.unlock(lock.getExternalLockId());
        accessLogger.record(LockAccessLog.builder()
                        .lockDeviceId(lockId)
                        .userId(userId)
                        .actorType(ActorType.STAFF)
                        .action(Action.REMOTE_UNLOCK)
                        .result(result.success() ? Result.SUCCESS : Result.FAILED)
                        .providerErrorCode(result.errorCode())
                        .latencyMs(result.latencyMs())
                        .occurredAt(now),
                result.message(), client.ipAddress(), client.userAgent());
        if (!result.success()) {
            throw new TtlockException(result.message(), result.errorCode(), null);
        }
        DoorKind kind = lock.getLockType() == LockType.ROOM ? DoorKind.ROOM : DoorKind.MAIN_ENTRANCE;
        return new UnlockResponse(kind, lock.getName(), "SUCCESS", clock.instant());
    }
}
