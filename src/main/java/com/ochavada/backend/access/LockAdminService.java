package com.ochavada.backend.access;

import com.ochavada.backend.access.dto.LockDtos.AccessLogEntry;
import com.ochavada.backend.access.dto.LockDtos.LockRequest;
import com.ochavada.backend.access.dto.LockDtos.LockResponse;
import com.ochavada.backend.access.dto.LockDtos.ProviderLock;
import com.ochavada.backend.access.dto.LockDtos.SyncResult;
import com.ochavada.backend.access.ttlock.TtlockDtos.LockDetail;
import com.ochavada.backend.access.ttlock.TtlockGateway;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Alta y mantenimiento de cerraduras, descubrimiento en TTLock y sincronización de batería. */
@Slf4j
@Service
public class LockAdminService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final long MAX_LOG_DAYS = 400;

    private final LockDeviceRepository repository;
    private final LockAccessLogRepository logRepository;
    private final RoomRepository roomRepository;
    private final TtlockGateway gateway;
    private final AuditService auditService;
    private final AccessProperties accessProperties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LockAdminService(LockDeviceRepository repository, LockAccessLogRepository logRepository,
                            RoomRepository roomRepository, TtlockGateway gateway, AuditService auditService,
                            AccessProperties accessProperties, PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.roomRepository = roomRepository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.accessProperties = accessProperties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LockResponse> list() {
        return repository.findAllByOrderByLockTypeAscNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public LockResponse create(LockRequest req) {
        if (repository.existsByExternalLockId(req.externalLockId())) {
            throw new ConflictException("lock.duplicate", "La cerradura TTLock " + req.externalLockId() + " ya está registrada");
        }
        Room room = resolveRoom(req, null);
        LockDevice lock = new LockDevice(req.externalLockId(), req.name().trim(), req.lockType(), room);
        apply(lock, req);
        repository.saveAndFlush(lock);
        auditService.record("lock.created", "lock_device", lock.getId(), Map.of("name", lock.getName(),
                "type", lock.getLockType().name()));
        return toResponse(lock);
    }

    @Transactional
    public LockResponse update(UUID id, LockRequest req) {
        LockDevice lock = find(id);
        lock.assertVersion(req.version());
        if (repository.existsByExternalLockIdAndIdNot(req.externalLockId(), id)) {
            throw new ConflictException("lock.duplicate", "La cerradura TTLock " + req.externalLockId() + " ya está registrada");
        }
        lock.setRoom(resolveRoom(req, id));
        lock.setExternalLockId(req.externalLockId());
        lock.setName(req.name().trim());
        lock.setLockType(req.lockType());
        apply(lock, req);
        repository.saveAndFlush(lock);
        auditService.record("lock.updated", "lock_device", id, Map.of("active", lock.isActive()));
        return toResponse(lock);
    }

    /** Solo se pueden borrar cerraduras sin historial; con historial deben desactivarse. */
    @Transactional
    public void delete(UUID id) {
        repository.delete(find(id));
        repository.flush();
    }

    /** Cerraduras de la cuenta TTLock, marcando las ya registradas. */
    @Transactional(readOnly = true)
    public List<ProviderLock> providerLocks() {
        Set<Long> registered = repository.findAll().stream().map(LockDevice::getExternalLockId)
                .collect(Collectors.toSet());
        return gateway.listAccountLocks().stream()
                .map(l -> new ProviderLock(l.lockId(), l.lockAlias() != null ? l.lockAlias() : l.lockName(),
                        l.lockMac() == null ? null : l.lockMac().toUpperCase(Locale.ROOT), l.electricQuantity(),
                        l.hasGateway() != null && l.hasGateway() == 1, registered.contains(l.lockId())))
                .toList();
    }

    /** Actualiza la batería de todas las cerraduras activas (cada consulta es independiente). */
    public SyncResult syncBatteries() {
        List<UUID> ids = tx.execute(s -> repository.findByActiveTrue().stream().map(LockDevice::getId).toList());
        int updated = 0;
        int failed = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            try {
                long externalId = tx.execute(s -> find(id).getExternalLockId());
                LockDetail detail = gateway.lockDetail(externalId);
                Instant now = clock.instant();
                tx.executeWithoutResult(s -> {
                    LockDevice lock = find(id);
                    lock.updateBattery(detail.electricQuantity(), now);
                    if (detail.hasGateway() != null) {
                        lock.setHasGateway(detail.hasGateway() == 1);
                    }
                    if (lock.getBatteryLevel() != null && lock.getBatteryLevel() <= accessProperties.lowBatteryThreshold()) {
                        log.warn("Batería baja en la cerradura '{}': {} %", lock.getName(), lock.getBatteryLevel());
                    }
                });
                updated++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("No se pudo sincronizar la cerradura {}: {}", id, ex.getMessage());
            }
        }
        return new SyncResult(updated, failed);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessLogEntry> accessLog(UUID lockId, Instant from, Instant to, int page, int size) {
        find(lockId);
        if (!to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > MAX_LOG_DAYS) {
            throw new BadRequestException("validation.date_range", "Rango de fechas no válido (máximo " + MAX_LOG_DAYS + " días)");
        }
        return PageResponse.of(logRepository.findByLockDeviceIdAndOccurredAtBetween(lockId, from, to,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                                Sort.by(Sort.Direction.DESC, "occurredAt"))),
                AccessLogEntry::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessLogEntry> reservationAccessLog(UUID reservationId, int page, int size) {
        return PageResponse.of(logRepository.findByReservationId(reservationId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                                Sort.by(Sort.Direction.DESC, "occurredAt"))),
                AccessLogEntry::from);
    }

    LockDevice find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("cerradura", id));
    }

    private Room resolveRoom(LockRequest req, UUID excludeId) {
        if (req.lockType() != LockType.ROOM) {
            return null;
        }
        Room room = roomRepository.findById(req.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("unidad", req.roomId()));
        boolean taken = excludeId == null
                ? repository.existsByRoomIdAndActiveTrue(room.getId())
                : repository.existsByRoomIdAndActiveTrueAndIdNot(room.getId(), excludeId);
        if (Boolean.TRUE.equals(req.active()) && taken) {
            throw new ConflictException("lock.room_taken",
                    "La unidad " + room.getNumber() + " ya tiene una cerradura activa");
        }
        return room;
    }

    private static void apply(LockDevice lock, LockRequest req) {
        lock.setLockMac(req.lockMac() == null || req.lockMac().isBlank() ? null : req.lockMac().toUpperCase(Locale.ROOT));
        lock.setGatewayId(req.gatewayId());
        if (req.hasGateway() != null) {
            lock.setHasGateway(req.hasGateway());
        }
        lock.setActive(req.active());
    }

    private LockResponse toResponse(LockDevice lock) {
        return LockResponse.from(lock, accessProperties.lowBatteryThreshold());
    }
}
