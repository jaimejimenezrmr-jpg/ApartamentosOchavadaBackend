package com.ochavada.backend.reservation.backoffice;

import java.util.Set;
import java.util.HashSet;
import com.ochavada.backend.availability.InventoryChangedEvent;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomBlockRepository;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.checkout.HoldReleaseService;
import com.ochavada.backend.checkout.HoldReleaseService.ReleaseOutcome;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.guest.GuestRepository;
import com.ochavada.backend.guestportal.GuestAccessService;
import com.ochavada.backend.housekeeping.HousekeepingService;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxService;
import com.ochavada.backend.notification.ReservationEmailHandler;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RatePlanRepository;
import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationCodeGenerator;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.StayPricer.PricedStay;
import com.ochavada.backend.reservation.backoffice.dto.ManualReservationRequest;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail;
import com.ochavada.backend.reservation.backoffice.dto.ReservationNotesRequest;
import com.ochavada.backend.reservation.backoffice.dto.StayChangeRequest;
import com.ochavada.backend.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operaciones de recepción sobre reservas: alta manual, cambio de estancia, asignación/cambio de unidad,
 * cancelación y no-show. Toda modificación bloquea la fila de la reserva y deja rastro de auditoría.
 */
@Service
public class ReservationCommandService {

    static final int MAX_MANUAL_NIGHTS = 365;
    private static final String OVERLAP_CONSTRAINT = "ex_reservation_room_overlap";

    private final ReservationRepository repository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final RoomBlockRepository blockRepository;
    private final RatePlanRepository ratePlanRepository;
    private final GuestRepository guestRepository;
    private final InventoryRepository inventoryRepository;
    private final StayPricer pricer;
    private final ReservationCodeGenerator codeGenerator;
    private final ReservationQueryService queryService;
    private final HoldReleaseService holdReleaseService;
    private final GuestAccessService guestAccessService;
    private final HousekeepingService housekeepingService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final PropertyCalendar calendar;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReservationCommandService(ReservationRepository repository, RoomTypeRepository roomTypeRepository,
                                     RoomRepository roomRepository, RoomBlockRepository blockRepository,
                                     RatePlanRepository ratePlanRepository, GuestRepository guestRepository,
                                     InventoryRepository inventoryRepository, StayPricer pricer,
                                     ReservationCodeGenerator codeGenerator, ReservationQueryService queryService,
                                     HoldReleaseService holdReleaseService, GuestAccessService guestAccessService,
                                     HousekeepingService housekeepingService, OutboxService outboxService,
                                     AuditService auditService, PropertyCalendar calendar,
                                     ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.events = events;
        this.repository = repository;
        this.roomTypeRepository = roomTypeRepository;
        this.roomRepository = roomRepository;
        this.blockRepository = blockRepository;
        this.ratePlanRepository = ratePlanRepository;
        this.guestRepository = guestRepository;
        this.inventoryRepository = inventoryRepository;
        this.pricer = pricer;
        this.codeGenerator = codeGenerator;
        this.queryService = queryService;
        this.holdReleaseService = holdReleaseService;
        this.guestAccessService = guestAccessService;
        this.housekeepingService = housekeepingService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.calendar = calendar;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ alta manual

    @Transactional
    public ReservationDetail create(ManualReservationRequest req) {
        validateDates(req.checkIn(), req.checkOut());
        RoomType roomType = roomTypeRepository.findById(req.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("tipología", req.roomTypeId()));
        if (!roomType.accommodates(req.adults(), req.children())) {
            throw new BusinessRuleException("reservation.occupancy_exceeded",
                    "La tipología admite como máximo " + roomType.getMaxOccupancy() + " personas ("
                            + roomType.getMaxAdults() + " adultos, " + roomType.getMaxChildren() + " niños)");
        }
        RatePlan plan = null;
        if (req.ratePlanId() != null) {
            plan = ratePlanRepository.findById(req.ratePlanId())
                    .orElseThrow(() -> new ResourceNotFoundException("plan de tarifa", req.ratePlanId()));
            if (!plan.getRoomType().getId().equals(roomType.getId())) {
                throw new BadRequestException("reservation.rate_plan_mismatch",
                        "El plan de tarifa no pertenece a la tipología indicada");
            }
        }
        PricedStay priced = pricer.price(plan, req.checkIn(), req.checkOut(), req.totalAmountOverride(),
                Boolean.TRUE.equals(req.overrideRestrictions()));

        Room room = req.roomId() != null
                ? requireRoomFree(findRoom(req.roomId()), req.checkIn(), req.checkOut())
                : pickFreeRoom(roomType, req.checkIn(), req.checkOut());
        requireTypeCapacity(room.getRoomType(), req.checkIn(), req.checkOut());

        Instant now = clock.instant();
        Guest holder = req.guestId() != null
                ? guestRepository.findById(req.guestId())
                        .orElseThrow(() -> new ResourceNotFoundException("huésped", req.guestId()))
                : guestRepository.save(newGuest(req.guest(), req.locale()));
        Reservation reservation = Reservation.manual(codeGenerator.nextCode(), req.channel(), roomType, room, plan,
                holder, req.checkIn(), req.checkOut(), req.adults(), req.children(), priced.total(),
                roomType.getCurrency(), req.paymentMode() == null ? PaymentMode.AT_PROPERTY : req.paymentMode(),
                CurrentUser.staffUserId().orElse(null), req.locale() == null ? holder.getPreferredLocale() : req.locale(),
                now);
        reservation.replaceNights(priced.nights());
        reservation.setChannelBookingReference(blankToNull(req.channelReference()));
        reservation.setSpecialRequests(blankToNull(req.specialRequests()));
        reservation.setInternalNotes(blankToNull(req.internalNotes()));
        reservation.setExpectedArrivalTime(req.expectedArrivalTime());
        saveAndFlush(reservation);

        Map<String, Object> details = new HashMap<>();
        details.put("code", reservation.getCode());
        details.put("channel", req.channel().name());
        details.put("total", priced.total());
        details.put("priceOverridden", req.totalAmountOverride() != null);
        if (!priced.overridden().isEmpty()) {
            details.put("restrictionsOverridden", priced.overridden().stream().map(v -> v.code().name()).toList());
        }
        auditService.record("reservation.created_manual", "reservation", reservation.getId(), details);
        events.publishEvent(new ReservationLifecycleEvent(reservation.getId(), ReservationLifecycleEvent.Type.CONFIRMED));
        if (Boolean.TRUE.equals(req.sendConfirmation())) {
            if (holder.getEmail() == null) {
                throw new BadRequestException("reservation.email_required",
                        "El titular no tiene email: no se puede enviar la confirmación");
            }
            outboxService.enqueue(OutboxDestination.EMAIL, "reservation", reservation.getId(),
                    ReservationEmailHandler.RESERVATION_CONFIRMED, Map.of("reservationId", reservation.getId()));
        }
        return queryService.toDetail(reservation);
    }

    // ------------------------------------------------------------------ edición

    @Transactional
    public ReservationDetail updateNotes(UUID id, ReservationNotesRequest req) {
        Reservation r = lock(id);
        r.assertVersion(req.version());
        r.setSpecialRequests(blankToNull(req.specialRequests()));
        r.setInternalNotes(blankToNull(req.internalNotes()));
        r.setExpectedArrivalTime(req.expectedArrivalTime());
        r.setChannelBookingReference(blankToNull(req.channelReference()));
        repository.flush();
        return queryService.toDetail(r);
    }

    @Transactional
    public ReservationDetail changeStay(UUID id, StayChangeRequest req) {
        Reservation r = lock(id);
        r.assertVersion(req.version());
        if (r.getStatus() == ReservationStatus.PENDING_PAYMENT) {
            throw new ConflictException("reservation.invalid_state",
                    "La reserva está pendiente de pago online; espere a que se confirme o caduque");
        }
        LocalDate today = calendar.today();
        if (r.getStatus() != ReservationStatus.CHECKED_IN) {
            validateDates(req.checkIn(), req.checkOut());
        } else if (!req.checkOut().isAfter(today)) {
            throw new BadRequestException("reservation.invalid_dates",
                    "Durante la estancia la nueva salida debe ser posterior a hoy");
        }
        if (!r.getRoomType().accommodates(req.adults(), req.children())) {
            throw new BusinessRuleException("reservation.occupancy_exceeded",
                    "La tipología no admite " + req.adults() + " adultos y " + req.children() + " niños");
        }
        PricedStay priced = pricer.price(r.getRatePlan(), req.checkIn(), req.checkOut(), req.totalAmountOverride(),
                Boolean.TRUE.equals(req.overrideRestrictions()));
        if (r.getRoom() == null) {
            r.assignRoom(pickFreeRoom(r.getRoomType(), req.checkIn(), req.checkOut()));
        } else if (blockRepository.existsOverlapping(r.getRoom().getId(), req.checkIn(), req.checkOut())) {
            throw new ConflictException("reservation.room_blocked",
                    "La unidad " + r.getRoom().getNumber() + " está bloqueada en las nuevas fechas");
        }
        Map<String, Object> before = Map.of("checkIn", r.getCheckInDate().toString(),
                "checkOut", r.getCheckOutDate().toString(), "total", r.getTotalAmount());
        LocalDate previousCheckIn = r.getCheckInDate();
        LocalDate previousCheckOut = r.getCheckOutDate();
        r.changeStay(req.checkIn(), req.checkOut(), req.adults(), req.children(), priced.total(), priced.nights());
        saveAndFlush(r);
        // El evento STAY_CHANGED cubre las fechas nuevas; las anteriores también cambian de disponibilidad.
        events.publishEvent(InventoryChangedEvent.nights(r.getRoomType().getId(), previousCheckIn, previousCheckOut));
        auditService.record("reservation.stay_changed", "reservation", id, Map.of("before", before,
                "after", Map.of("checkIn", req.checkIn().toString(), "checkOut", req.checkOut().toString(),
                        "total", priced.total())));
        events.publishEvent(new ReservationLifecycleEvent(id, ReservationLifecycleEvent.Type.STAY_CHANGED));
        return queryService.toDetail(r);
    }

    @Transactional
    public ReservationDetail assignRoom(UUID id, UUID roomId) {
        Reservation r = lock(id);
        Room newRoom = findRoom(roomId);
        Room previous = r.getRoom();
        if (previous != null && previous.getId().equals(roomId)) {
            return queryService.toDetail(r);
        }
        LocalDate today = calendar.today();
        LocalDate from = r.getStatus() == ReservationStatus.CHECKED_IN && today.isAfter(r.getCheckInDate())
                ? today : r.getCheckInDate();
        requireRoomFree(newRoom, from, r.getCheckOutDate());
        r.assignRoom(newRoom);
        saveAndFlush(r);
        Set<UUID> affectedTypes = new HashSet<>();
        affectedTypes.add(newRoom.getRoomType().getId());
        if (previous != null) {
            affectedTypes.add(previous.getRoomType().getId());
        }
        if (!affectedTypes.equals(Set.of(r.getRoomType().getId()))) {
            // Cambio de unidad entre tipologías (upgrade): cambia el inventario de ambas.
            affectedTypes.add(r.getRoomType().getId());
            events.publishEvent(InventoryChangedEvent.nights(affectedTypes, from, r.getCheckOutDate()));
        }
        if (r.getStatus() == ReservationStatus.CHECKED_IN && previous != null) {
            // Cambio de unidad durante la estancia: la anterior queda pendiente de limpieza.
            housekeepingService.scheduleCheckoutCleaning(previous, id);
        }
        auditService.record("reservation.room_assigned", "reservation", id, Map.of(
                "from", previous == null ? "-" : previous.getNumber(), "to", newRoom.getNumber()));
        return queryService.toDetail(r);
    }

    // ------------------------------------------------------------------ cancelación y no-show

    /**
     * Cancela la reserva y revoca el acceso del huésped. Los cobros no se devuelven automáticamente:
     * el reembolso (total, parcial o ninguno según la política) se registra aparte.
     */
    public ReservationDetail cancel(UUID id, String reason) {
        ReservationStatus status = tx.execute(s -> repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", id)).getStatus());
        if (status == ReservationStatus.PENDING_PAYMENT) {
            if (holdReleaseService.release(id, reason, false) == ReleaseOutcome.PAYMENT_IN_PROGRESS) {
                throw new ConflictException("reservation.payment_in_progress",
                        "Hay un pago online en curso; espere a que se confirme antes de cancelar");
            }
        } else {
            tx.executeWithoutResult(s -> {
                Reservation r = lock(id);
                r.cancel(reason, clock.instant());
                guestAccessService.revokeAll(id);
                auditService.record("reservation.cancelled", "reservation", id, Map.of("reason", reason,
                        "paidAmount", r.getPaidAmount()));
                events.publishEvent(new ReservationLifecycleEvent(id, ReservationLifecycleEvent.Type.CANCELLED));
            });
        }
        return tx.execute(s -> queryService.toDetail(repository.findById(id).orElseThrow()));
    }

    @Transactional
    public ReservationDetail markNoShow(UUID id) {
        Reservation r = lock(id);
        if (calendar.today().isBefore(r.getCheckInDate())) {
            throw new ConflictException("reservation.too_early",
                    "No se puede marcar como no presentado antes del día de llegada");
        }
        r.markNoShow();
        guestAccessService.revokeAll(id);
        LocalDate releasedFrom = calendar.today().isAfter(r.getCheckInDate()) ? calendar.today() : r.getCheckInDate();
        if (releasedFrom.isBefore(r.getCheckOutDate())) {
            events.publishEvent(InventoryChangedEvent.nights(r.getRoomType().getId(), releasedFrom, r.getCheckOutDate()));
        }
        auditService.record("reservation.no_show", "reservation", id, Map.of("code", r.getCode()));
        repository.flush();
        return queryService.toDetail(r);
    }

    // ------------------------------------------------------------------ internos

    private Reservation lock(UUID id) {
        return repository.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("reserva", id));
    }

    private void saveAndFlush(Reservation reservation) {
        try {
            repository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains(OVERLAP_CONSTRAINT)) {
                throw new ConflictException("reservation.room_unavailable",
                        "La unidad ya está ocupada en esas fechas por otra reserva");
            }
            throw ex;
        }
    }

    private Room findRoom(UUID id) {
        return roomRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("unidad", id));
    }

    private Room requireRoomFree(Room room, LocalDate from, LocalDate to) {
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new ConflictException("reservation.room_not_active",
                    "La unidad " + room.getNumber() + " no está operativa (" + room.getStatus() + ")");
        }
        if (blockRepository.existsOverlapping(room.getId(), from, to)) {
            throw new ConflictException("reservation.room_blocked",
                    "La unidad " + room.getNumber() + " está bloqueada en esas fechas");
        }
        return room;
    }

    private Room pickFreeRoom(RoomType roomType, LocalDate from, LocalDate to) {
        List<UUID> free = inventoryRepository.freeRoomIdsForStay(roomType.getId(), from, to);
        if (free.isEmpty()) {
            throw new ConflictException("reservation.sold_out",
                    "No hay unidades libres de " + roomType.getName() + " en esas fechas");
        }
        return roomRepository.getReferenceById(free.getFirst());
    }

    /**
     * Asignar una unidad libre no basta: si hay reservas de OTA sin unidad asignada, esas unidades ya
     * están comprometidas aunque el rack las muestre vacías.
     */
    private void requireTypeCapacity(RoomType roomType, LocalDate from, LocalDate to) {
        int units = inventoryRepository.availableUnitsForStay(List.of(roomType.getId()), from, to)
                .getOrDefault(roomType.getId(), 0);
        if (units <= 0) {
            throw new ConflictException("reservation.sold_out",
                    "No queda inventario de " + roomType.getName() + ": hay reservas pendientes de asignar unidad");
        }
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn.isBefore(calendar.today())) {
            throw new BadRequestException("reservation.past_date", "La fecha de entrada no puede ser anterior a hoy");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_MANUAL_NIGHTS) {
            throw new BadRequestException("reservation.stay_too_long",
                    "Una reserva no puede superar " + MAX_MANUAL_NIGHTS + " noches");
        }
    }

    private static Guest newGuest(ManualReservationRequest.NewGuest g, String locale) {
        Guest guest = new Guest(g.firstName().trim(), g.lastName1().trim(), blankToNull(g.lastName2()));
        guest.setEmail(g.email());
        guest.setPhone(blankToNull(g.phone()));
        guest.setCountryCode(g.countryCode());
        if (locale != null) {
            guest.setPreferredLocale(locale);
        }
        return guest;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
