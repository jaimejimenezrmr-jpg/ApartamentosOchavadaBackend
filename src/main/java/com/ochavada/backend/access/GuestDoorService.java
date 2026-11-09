package com.ochavada.backend.access;

import com.ochavada.backend.access.LockAccessLog.Action;
import com.ochavada.backend.access.LockAccessLog.ActorType;
import com.ochavada.backend.access.LockAccessLog.DenialReason;
import com.ochavada.backend.access.LockAccessLog.LockAccessLogBuilder;
import com.ochavada.backend.access.LockAccessLog.Result;
import com.ochavada.backend.access.dto.DoorDtos.Door;
import com.ochavada.backend.access.dto.DoorDtos.DoorsResponse;
import com.ochavada.backend.access.dto.DoorDtos.SelfCheckIn;
import com.ochavada.backend.access.dto.DoorDtos.UnlockResponse;
import com.ochavada.backend.access.ttlock.TtlockException;
import com.ochavada.backend.access.ttlock.TtlockGateway;
import com.ochavada.backend.access.ttlock.TtlockGateway.UnlockResult;
import com.ochavada.backend.audit.AuditLog;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.auth.ClientInfo;
import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ServiceUnavailableException;
import com.ochavada.backend.common.web.RateLimiter;
import com.ochavada.backend.common.web.TooManyRequestsException;
import com.ochavada.backend.guestportal.GuestAccessService;
import com.ochavada.backend.guestportal.GuestAccessService.GuestContext;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.reservation.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Llave virtual del huésped: los botones «Abrir Entrada Principal» y «Abrir Mi Habitación».
 * <p>
 * Cada apertura se valida en el servidor, en este orden:
 * <ol>
 *     <li>JWT de huésped válido y enlace de acceso no revocado ({@link GuestAccessService}).</li>
 *     <li>Reserva en estado {@code CHECKED_IN}.</li>
 *     <li>Instante actual dentro de la ventana de la estancia ({@link StayWindow}).</li>
 *     <li>Cerradura activa: el portal del edificio, o la de la unidad asignada a la reserva.</li>
 *     <li>Límite de aperturas por reserva.</li>
 * </ol>
 * Solo entonces se ordena la apertura a TTLock (servidor a servidor). Todos los intentos, también los
 * denegados y fallidos, quedan en {@code lock_access_log}.
 * <p>
 * Para un funcionamiento desatendido, el huésped puede hacer el autocheck-in desde el portal el día de
 * llegada si se cumplen las condiciones configuradas (unidad lista, saldo pagado).
 */
@Service
public class GuestDoorService {

    /** Solo una unidad inspeccionada (LISTA) recibe huéspedes: «limpio» aún está pendiente de inspección. */
    private static final Set<HousekeepingStatus> READY = EnumSet.of(HousekeepingStatus.INSPECTED);

    private final GuestAccessService guestAccessService;
    private final ReservationRepository reservationRepository;
    private final LockDeviceRepository lockRepository;
    private final TtlockGateway gateway;
    private final LockAccessLogger accessLogger;
    private final RateLimiter rateLimiter;
    private final StayWindow window;
    private final AccessProperties properties;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public GuestDoorService(GuestAccessService guestAccessService, ReservationRepository reservationRepository,
                            LockDeviceRepository lockRepository, TtlockGateway gateway, LockAccessLogger accessLogger,
                            RateLimiter rateLimiter, StayWindow window, AccessProperties properties,
                            AuditService auditService, ApplicationEventPublisher events,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.events = events;
        this.guestAccessService = guestAccessService;
        this.reservationRepository = reservationRepository;
        this.lockRepository = lockRepository;
        this.gateway = gateway;
        this.accessLogger = accessLogger;
        this.rateLimiter = rateLimiter;
        this.window = window;
        this.properties = properties;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** Estado de los botones y del autocheck-in, sin contactar con las cerraduras. */
    public DoorsResponse doors() {
        GuestContext ctx = guestAccessService.currentContext();
        return readTx.execute(s -> {
            Reservation r = reservationRepository.findById(ctx.reservationId()).orElseThrow();
            Instant now = clock.instant();
            List<Door> doors = Arrays.stream(DoorKind.values()).map(kind -> {
                Optional<LockDevice> lock = resolveLock(kind, r);
                DenialReason denial = evaluate(r, lock, now);
                return new Door(kind, lock.map(LockDevice::getName).orElse(defaultName(kind)), denial == null,
                        denial == null ? null : denial.name());
            }).toList();
            List<String> blockers = selfCheckInBlockers(r, now);
            return new DoorsResponse(r.getCode(), r.getStatus(), window.opensAt(r), window.closesAt(r),
                    new SelfCheckIn(blockers.isEmpty(), blockers), doors);
        });
    }

    public UnlockResponse unlock(DoorKind kind, ClientInfo client) {
        GuestContext ctx = guestAccessService.currentContext();
        Instant now = clock.instant();
        Decision decision = Objects.requireNonNull(readTx.execute(s -> {
            Reservation r = reservationRepository.findById(ctx.reservationId()).orElseThrow();
            Optional<LockDevice> lock = resolveLock(kind, r);
            return new Decision(lock.map(LockDevice::getId).orElse(null),
                    lock.map(LockDevice::getExternalLockId).orElse(null),
                    lock.map(LockDevice::getName).orElse(defaultName(kind)), evaluate(r, lock, now));
        }));

        if (decision.denial() != null) {
            log(ctx, decision, now, Result.DENIED, decision.denial(), null, null, client);
            throw new AccessDeniedProblem(decision.denial(), explain(decision.denial()));
        }
        try {
            rateLimiter.check(RateLimiter.Bucket.GUEST_UNLOCK, ctx.reservationId().toString());
        } catch (TooManyRequestsException ex) {
            log(ctx, decision, now, Result.DENIED, DenialReason.RATE_LIMITED, null, null, client);
            throw ex;
        }

        UnlockResult result;
        try {
            result = gateway.unlock(decision.externalLockId());
        } catch (ServiceUnavailableException ex) {
            log(ctx, decision, now, Result.FAILED, null, null, "Apertura remota no configurada", client);
            throw ex;
        }
        if (!result.success()) {
            log(ctx, decision, now, Result.FAILED, null, result, result.message(), client);
            throw new TtlockException(result.message() + ". Inténtelo de nuevo en unos segundos o contacte con el "
                    + "alojamiento", result.errorCode(), null);
        }
        log(ctx, decision, now, Result.SUCCESS, null, result, null, client);
        return new UnlockResponse(kind, decision.lockName(), "SUCCESS", clock.instant());
    }

    /**
     * Autocheck-in desde el portal. Idempotente: si ya está hecho, devuelve el estado actual.
     */
    public DoorsResponse selfCheckIn() {
        GuestContext ctx = guestAccessService.currentContext();
        tx.executeWithoutResult(s -> {
            Reservation r = reservationRepository.findForUpdate(ctx.reservationId()).orElseThrow();
            if (r.getStatus() == ReservationStatus.CHECKED_IN) {
                return;
            }
            Instant now = clock.instant();
            List<String> blockers = selfCheckInBlockers(r, now);
            if (!blockers.isEmpty()) {
                throw new ConflictException("access.self_check_in_unavailable",
                        "El check-in aún no está disponible para esta reserva", Map.of("blockers", blockers));
            }
            r.checkIn(now);
            auditService.record(AuditLog.ActorType.GUEST, null, "reservation.self_checked_in", "reservation",
                    r.getId(), Map.of("accessId", ctx.accessId().toString(), "room", r.getRoom().getNumber()));
            events.publishEvent(new ReservationLifecycleEvent(r.getId(), ReservationLifecycleEvent.Type.CHECKED_IN));
        });
        return doors();
    }

    // ------------------------------------------------------------------ reglas

    private DenialReason evaluate(Reservation r, Optional<LockDevice> lock, Instant now) {
        if (r.getStatus() != ReservationStatus.CHECKED_IN) {
            return DenialReason.RESERVATION_NOT_CHECKED_IN;
        }
        if (!window.contains(r, now)) {
            return DenialReason.OUTSIDE_STAY_WINDOW;
        }
        if (lock.isEmpty()) {
            return DenialReason.LOCK_NOT_ASSIGNED;
        }
        return null;
    }

    private List<String> selfCheckInBlockers(Reservation r, Instant now) {
        List<String> blockers = new ArrayList<>();
        if (r.getStatus() == ReservationStatus.CHECKED_IN) {
            blockers.add("ALREADY_CHECKED_IN");
            return blockers;
        }
        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            blockers.add("RESERVATION_NOT_CONFIRMED");
            return blockers;
        }
        if (now.isBefore(window.opensAt(r))) {
            blockers.add("TOO_EARLY");
        }
        if (!now.isBefore(window.closesAt(r))) {
            blockers.add("STAY_ENDED");
        }
        Room room = r.getRoom();
        if (room == null) {
            blockers.add("ROOM_NOT_ASSIGNED");
        } else if (room.getStatus() != RoomStatus.ACTIVE
                || (properties.selfCheckInRequiresCleanRoom() && !READY.contains(room.getHousekeepingStatus()))) {
            blockers.add("ROOM_NOT_READY");
        }
        if (properties.selfCheckInRequiresPayment() && r.balanceDue().signum() > 0) {
            blockers.add("BALANCE_DUE");
        }
        if (properties.selfCheckInRequiresOnlineCheckin() && r.getOnlineCheckinCompletedAt() == null) {
            blockers.add("ONLINE_CHECKIN_PENDING");
        }
        return blockers;
    }

    private Optional<LockDevice> resolveLock(DoorKind kind, Reservation r) {
        return switch (kind) {
            case MAIN_ENTRANCE -> lockRepository.findFirstByLockTypeAndActiveTrueOrderByNameAsc(LockType.MAIN_ENTRANCE);
            case ROOM -> r.getRoom() == null ? Optional.empty()
                    : lockRepository.findFirstByRoomIdAndActiveTrue(r.getRoom().getId());
        };
    }

    private static String defaultName(DoorKind kind) {
        return kind == DoorKind.MAIN_ENTRANCE ? "Entrada principal" : "Mi habitación";
    }

    private static String explain(DenialReason reason) {
        return switch (reason) {
            case RESERVATION_NOT_CHECKED_IN -> "Las puertas se activan tras completar el check-in";
            case OUTSIDE_STAY_WINDOW -> "Las puertas solo se pueden abrir durante su estancia";
            case LOCK_NOT_ASSIGNED -> "Esta puerta no tiene apertura remota; contacte con el alojamiento";
            default -> "No se puede abrir la puerta en este momento";
        };
    }

    private void log(GuestContext ctx, Decision decision, Instant at, Result result, DenialReason denial,
                     UnlockResult unlock, String message, ClientInfo client) {
        LockAccessLogBuilder entry = LockAccessLog.builder()
                .lockDeviceId(decision.lockId())
                .reservationId(ctx.reservationId())
                .guestAccessId(ctx.accessId())
                .actorType(ActorType.GUEST)
                .action(Action.REMOTE_UNLOCK)
                .result(result)
                .denialReason(denial)
                .providerErrorCode(unlock == null ? null : unlock.errorCode())
                .latencyMs(unlock == null ? null : unlock.latencyMs())
                .occurredAt(at);
        accessLogger.record(entry, message, client.ipAddress(), client.userAgent());
    }

    private record Decision(UUID lockId, Long externalLockId, String lockName, DenialReason denial) {
    }
}
