package com.ochavada.backend.reservation.backoffice;

import com.ochavada.backend.availability.InventoryChangedEvent;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.checkin.TravelerService;
import com.ochavada.backend.checkin.dto.TravelersDtos.Issue;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.housekeeping.HousekeepingService;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check-in y check-out en recepción.
 * <p>
 * El check-in habilita la apertura remota de puertas del portal del huésped (fase IoT), que exige
 * estado {@code CHECKED_IN} además de la ventana horaria de la estancia.
 */
@Service
public class FrontDeskService {

    private static final Set<HousekeepingStatus> READY = EnumSet.of(HousekeepingStatus.CLEAN, HousekeepingStatus.INSPECTED);

    private final ReservationRepository repository;
    private final ReservationQueryService queryService;
    private final HousekeepingService housekeepingService;
    private final AuditService auditService;
    private final TravelerService travelerService;
    private final ApplicationEventPublisher events;
    private final PropertyCalendar calendar;
    private final Clock clock;

    public FrontDeskService(ReservationRepository repository, ReservationQueryService queryService,
                            HousekeepingService housekeepingService, AuditService auditService,
                            TravelerService travelerService, ApplicationEventPublisher events,
                            PropertyCalendar calendar, Clock clock) {
        this.travelerService = travelerService;
        this.events = events;
        this.repository = repository;
        this.queryService = queryService;
        this.housekeepingService = housekeepingService;
        this.auditService = auditService;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * @param force permite el check-in aunque la unidad no figure como limpia (p. ej. repaso hecho sin
     *              registrar la tarea)
     */
    @Transactional
    public ReservationDetail checkIn(UUID id, boolean force) {
        Reservation r = lock(id);
        LocalDate today = calendar.today();
        if (today.isBefore(r.getCheckInDate())) {
            throw new ConflictException("reservation.too_early",
                    "La llegada es el " + r.getCheckInDate() + ": no se puede hacer el check-in antes");
        }
        if (!today.isBefore(r.getCheckOutDate())) {
            throw new ConflictException("reservation.stay_ended", "La fecha de salida de la reserva ya ha pasado");
        }
        Room room = r.getRoom();
        if (room != null) {
            if (room.getStatus() != RoomStatus.ACTIVE) {
                throw new ConflictException("reservation.room_not_active",
                        "La unidad " + room.getNumber() + " no está operativa; asigne otra");
            }
            if (!force && !READY.contains(room.getHousekeepingStatus())) {
                throw new ConflictException("reservation.room_not_ready",
                        "La unidad " + room.getNumber() + " figura como " + room.getHousekeepingStatus()
                                + ". Confirme con limpieza o fuerce el check-in",
                        Map.of("housekeepingStatus", room.getHousekeepingStatus().name()));
            }
        }
        // RD 933/2021: los datos de todos los viajeros son necesarios para el parte de entrada.
        List<Issue> travelerIssues = travelerService.issues(r);
        if (!force && !travelerIssues.isEmpty()) {
            throw new ConflictException("reservation.travelers_incomplete",
                    "Faltan datos de los viajeros para el parte de entrada. Complételos o fuerce el check-in "
                            + "(el parte quedará pendiente)", Map.of("issues", travelerIssues));
        }
        r.checkIn(clock.instant());
        repository.flush();
        auditService.record("reservation.checked_in", "reservation", id, Map.of("room", r.getRoom().getNumber(),
                "forced", force && (!READY.contains(r.getRoom().getHousekeepingStatus()) || !travelerIssues.isEmpty())));
        events.publishEvent(new ReservationLifecycleEvent(id, ReservationLifecycleEvent.Type.CHECKED_IN));
        return queryService.toDetail(r);
    }

    /**
     * @param force permite la salida con saldo pendiente (queda reflejado como deuda)
     */
    @Transactional
    public ReservationDetail checkOut(UUID id, boolean force) {
        Reservation r = lock(id);
        if (!force && r.balanceDue().signum() > 0) {
            throw new ConflictException("reservation.balance_due",
                    "La reserva tiene " + r.balanceDue() + " " + r.getCurrency() + " pendientes de cobro",
                    Map.of("balanceDue", r.balanceDue()));
        }
        Instant now = clock.instant();
        LocalDate plannedCheckOut = r.getCheckOutDate();
        r.checkOut(now, calendar.today());
        if (r.getCheckOutDate().isBefore(plannedCheckOut)) {
            // Salida anticipada: las noches restantes vuelven a la venta.
            events.publishEvent(InventoryChangedEvent.nights(r.getRoomType().getId(), r.getCheckOutDate(), plannedCheckOut));
        }
        housekeepingService.scheduleCheckoutCleaning(r.getRoom(), id);
        repository.flush();
        auditService.record("reservation.checked_out", "reservation", id, Map.of(
                "room", r.getRoom().getNumber(),
                "earlyDeparture", r.getCheckOutDate().isBefore(plannedCheckOut),
                "balanceDue", r.balanceDue()));
        return queryService.toDetail(r);
    }

    private Reservation lock(UUID id) {
        return repository.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("reserva", id));
    }
}
