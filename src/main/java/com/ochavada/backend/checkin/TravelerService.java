package com.ochavada.backend.checkin;

import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.checkin.dto.TravelersDtos.Issue;
import com.ochavada.backend.checkin.dto.TravelersDtos.TravelersRequest;
import com.ochavada.backend.checkin.dto.TravelersDtos.TravelersResponse;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.guest.GuestRepository;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationOccupant;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de viajeros de una reserva: lo usan el check-in online del portal del huésped y recepción.
 * <p>
 * Al guardar se exigen todas las reglas del parte de viajeros: así, una reserva con check-in online
 * completado siempre puede comunicarse a SES.HOSPEDAJES sin errores de datos.
 */
@Service
public class TravelerService {

    private static final Set<ReservationStatus> EDITABLE = EnumSet.of(ReservationStatus.CONFIRMED,
            ReservationStatus.CHECKED_IN);

    private final ReservationRepository reservationRepository;
    private final GuestRepository guestRepository;
    private final TravelerValidator validator;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TravelerService(ReservationRepository reservationRepository, GuestRepository guestRepository,
                           TravelerValidator validator, AuditService auditService, ApplicationEventPublisher events,
                           Clock clock) {
        this.reservationRepository = reservationRepository;
        this.guestRepository = guestRepository;
        this.validator = validator;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TravelersResponse view(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", reservationId));
        return toResponse(r, currentTravelers(r));
    }

    @Transactional
    public TravelersResponse update(UUID reservationId, TravelersRequest request, boolean byGuest) {
        Reservation r = reservationRepository.findForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", reservationId));
        if (!EDITABLE.contains(r.getStatus())) {
            throw new ConflictException("checkin.not_editable",
                    "Los datos de los viajeros no pueden modificarse en una reserva " + r.getStatus());
        }
        List<Issue> issues = validator.validate(request.travelers(), r.getCheckInDate(), r.guestCount());
        if (!issues.isEmpty()) {
            throw new BusinessRuleException("checkin.invalid_travelers",
                    "Faltan datos o hay datos incorrectos en los viajeros", Map.of("issues", issues));
        }
        Instant now = clock.instant();

        TravelerData holderData = request.travelers().getFirst();
        holderData.applyTo(r.getHolder());
        r.setHolderRelationship(holderData.relationship(), now);

        // Reutiliza las fichas de acompañantes ya registradas (primero por documento, luego por posición)
        // para no duplicar huéspedes cada vez que se corrigen los datos.
        List<Guest> pool = new ArrayList<>(companionGuests(r));
        List<ReservationOccupant> companions = new ArrayList<>();
        for (int i = 1; i < request.travelers().size(); i++) {
            TravelerData data = request.travelers().get(i);
            Guest guest = takeMatching(pool, data);
            if (guest == null) {
                guest = new Guest(data.firstName().trim(), data.lastName1().trim(), null);
                data.applyTo(guest);
                guestRepository.save(guest);
            } else {
                data.applyTo(guest);
            }
            // Instantes crecientes para conservar el orden de los acompañantes.
            companions.add(new ReservationOccupant(guest.getId(), false, data.relationship(), now.plusMillis(i)));
        }
        r.replaceCompanions(companions);
        r.markOnlineCheckinCompleted(now);
        reservationRepository.flush();

        UUID staffId = CurrentUser.staffUserId().orElse(null);
        auditService.record(byGuest ? ActorType.GUEST : ActorType.STAFF, staffId, "reservation.travelers_registered",
                "reservation", r.getId(), Map.of("travelers", request.travelers().size()));
        events.publishEvent(new ReservationLifecycleEvent(r.getId(), ReservationLifecycleEvent.Type.TRAVELERS_UPDATED));
        return toResponse(r, currentTravelers(r));
    }

    /** Viajeros actuales: titular primero y acompañantes por orden de registro. */
    @Transactional(readOnly = true)
    public List<TravelerData> currentTravelers(Reservation r) {
        List<TravelerData> result = new ArrayList<>();
        // El parentesco del titular suele ser nulo: findFirst() no admite elementos nulos, de ahí el orden.
        String holderRelationship = r.getOccupants().stream().filter(ReservationOccupant::isHolder)
                .findFirst().map(ReservationOccupant::getRelationship).orElse(null);
        result.add(TravelerData.from(r.getHolder(), holderRelationship));
        List<ReservationOccupant> companions = r.getOccupants().stream().filter(o -> !o.isHolder())
                .sorted(Comparator.comparing(ReservationOccupant::getCreatedAt)).toList();
        Map<UUID, Guest> guests = new HashMap<>();
        guestRepository.findAllById(companions.stream().map(ReservationOccupant::getGuestId).toList())
                .forEach(g -> guests.put(g.getId(), g));
        for (ReservationOccupant o : companions) {
            Guest g = guests.get(o.getGuestId());
            if (g != null) {
                result.add(TravelerData.from(g, o.getRelationship()));
            }
        }
        return result;
    }

    /** ¿Están completos los datos para enviar el parte de viajeros? */
    @Transactional(readOnly = true)
    public List<Issue> issues(Reservation r) {
        return validator.validate(currentTravelers(r), r.getCheckInDate(), r.guestCount());
    }

    private static Guest takeMatching(List<Guest> pool, TravelerData data) {
        if (pool.isEmpty()) {
            return null;
        }
        if (data.documentNumber() != null && !data.documentNumber().isBlank()) {
            String document = data.documentNumber().trim().toUpperCase(Locale.ROOT);
            for (int i = 0; i < pool.size(); i++) {
                if (document.equals(pool.get(i).getDocumentNumber())) {
                    return pool.remove(i);
                }
            }
        }
        return pool.remove(0);
    }

    private List<Guest> companionGuests(Reservation r) {
        List<UUID> ids = r.getOccupants().stream().filter(o -> !o.isHolder())
                .sorted(Comparator.comparing(ReservationOccupant::getCreatedAt))
                .map(ReservationOccupant::getGuestId).toList();
        Map<UUID, Guest> found = new HashMap<>();
        guestRepository.findAllById(ids).forEach(g -> found.put(g.getId(), g));
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    private TravelersResponse toResponse(Reservation r, List<TravelerData> travelers) {
        List<Issue> issues = validator.validate(travelers, r.getCheckInDate(), r.guestCount());
        return new TravelersResponse(r.getCode(), r.guestCount(), issues.isEmpty(), r.getOnlineCheckinCompletedAt(),
                travelers, issues);
    }
}
