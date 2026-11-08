package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.availability.InventoryChangedEvent;
import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.channel.beds24.Beds24Dtos.Booking;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.guest.GuestRepository;
import com.ochavada.backend.guestportal.GuestAccessService;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RatePlanRepository;
import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationChannel;
import com.ochavada.backend.reservation.ReservationCodeGenerator;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.StayPricer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importa una reserva de Beds24 (Booking.com, Airbnb, Expedia, Google...) al PMS, o aplica sus cambios.
 *
 * <ul>
 *     <li>Las reservas de OTA nunca se rechazan: la OTA ya las ha aceptado. Se asigna la unidad indicada
 *     por Beds24 si está libre, si no otra libre de la tipología y, si no queda ninguna, la reserva queda
 *     sin unidad (resta inventario y recepción ve el aviso en {@code channelSyncNote}).</li>
 *     <li>Las reservas propias (web, teléfono, mostrador) que Beds24 devuelve como eco se reconocen por el
 *     identificador o por el localizador ({@code apiReference}) y no se tocan: aquí manda el PMS.</li>
 *     <li>Los cambios que no pueden aplicarse automáticamente (llegada modificada durante la estancia,
 *     cambio de tipología, cancelación de una estancia en curso) no fallan: se anotan para recepción.</li>
 * </ul>
 * Idempotente: importar dos veces el mismo estado no produce cambios.
 */
@Slf4j
@Component
public class Beds24BookingImporter {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ReservationRepository reservationRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final RatePlanRepository ratePlanRepository;
    private final GuestRepository guestRepository;
    private final InventoryRepository inventoryRepository;
    private final ReservationCodeGenerator codeGenerator;
    private final GuestAccessService guestAccessService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Beds24Properties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    public Beds24BookingImporter(ReservationRepository reservationRepository, RoomTypeRepository roomTypeRepository,
                                 RoomRepository roomRepository, RatePlanRepository ratePlanRepository,
                                 GuestRepository guestRepository, InventoryRepository inventoryRepository,
                                 ReservationCodeGenerator codeGenerator, GuestAccessService guestAccessService,
                                 AuditService auditService, ApplicationEventPublisher events,
                                 Beds24Properties properties, JdbcClient jdbc, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.roomRepository = roomRepository;
        this.ratePlanRepository = ratePlanRepository;
        this.guestRepository = guestRepository;
        this.inventoryRepository = inventoryRepository;
        this.codeGenerator = codeGenerator;
        this.guestAccessService = guestAccessService;
        this.auditService = auditService;
        this.events = events;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ImportResult importBooking(Booking b) {
        if (b.id() == null) {
            return ImportResult.IGNORED;
        }
        // Serializa webhook, sondeo e importación manual de la misma reserva entre instancias.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "beds24:booking:" + b.id())
                .query().singleRow();

        Optional<Reservation> existing = reservationRepository.findByBeds24BookingId(b.id());
        if (existing.isEmpty() && notBlank(b.apiReference())) {
            Optional<Reservation> own = reservationRepository.findByCode(b.apiReference().trim())
                    .filter(Reservation::isLocallyOriginated);
            if (own.isPresent()) {
                if (own.get().getBeds24BookingId() == null) {
                    reservationRepository.linkBeds24Booking(own.get().getId(), b.id());
                }
                return ImportResult.OWN_ECHO;
            }
        }
        if (existing.isPresent() && existing.get().isLocallyOriginated()) {
            return ImportResult.OWN_ECHO;
        }

        String status = b.status() == null ? "" : b.status().trim().toLowerCase(Locale.ROOT);
        boolean active = status.equals("confirmed") || status.equals("new");
        if (existing.isEmpty()) {
            return active ? create(b) : ImportResult.IGNORED;
        }
        if (status.equals("cancelled")) {
            return cancel(existing.get(), b);
        }
        return active ? update(existing.get(), b) : ImportResult.IGNORED;
    }

    // ------------------------------------------------------------------ alta

    private ImportResult create(Booking b) {
        RoomType roomType = mappedRoomType(b);
        if (!validDates(b)) {
            log.warn("Beds24: reserva {} con fechas no válidas ({} → {}); se ignora", b.id(), b.arrival(), b.departure());
            return ImportResult.IGNORED;
        }
        LocalDate checkIn = b.arrival();
        LocalDate checkOut = b.departure();
        int adults = adults(b);
        int children = children(b);
        BigDecimal total = money(b.price());
        ReservationChannel channel = channelOf(b);

        boolean typeHasInventory = inventoryRepository.availableUnitsForStay(List.of(roomType.getId()), checkIn, checkOut)
                .getOrDefault(roomType.getId(), 0) > 0;
        Room room = chooseRoom(roomType, b.unitId(), checkIn, checkOut, null).orElse(null);
        RatePlan plan = defaultPlan(roomType).orElse(null);

        Guest holder = guestRepository.save(newGuest(b));
        Reservation r = Reservation.fromChannel(codeGenerator.nextCode(), channel, b.id(),
                truncate(blankToNull(b.apiReference()), 100), roomType, room, plan, holder, checkIn, checkOut,
                adults, children, total, money(b.commission()), roomType.getCurrency(), paymentModeOf(b),
                holder.getPreferredLocale(), clock.instant());
        r.replaceNights(StayPricer.distribute(total, checkIn, checkOut, plan));
        r.setSpecialRequests(blankToNull(joinNonBlank(b.comments(), b.message())));
        if (room == null || !typeHasInventory) {
            r.recordChannelSyncNote(room == null
                    ? "Importada sin unidad: no había ninguna libre. Asigne una desde el rack (posible sobreventa)."
                    : "Importada sin inventario libre en la tipología: revise el rack (posible sobreventa).");
        }
        reservationRepository.saveAndFlush(r);

        Map<String, Object> details = new HashMap<>();
        details.put("beds24BookingId", b.id());
        details.put("channel", channel.name());
        details.put("code", r.getCode());
        details.put("room", room == null ? "-" : room.getNumber());
        details.put("overbooking", room == null || !typeHasInventory);
        auditService.record(ActorType.WEBHOOK, null, "reservation.imported_channel", "reservation", r.getId(), details);
        events.publishEvent(new ReservationLifecycleEvent(r.getId(), ReservationLifecycleEvent.Type.CONFIRMED));
        log.info("Reserva {} de {} importada como {} (unidad {})", b.id(), channel, r.getCode(),
                room == null ? "sin asignar" : room.getNumber());
        return ImportResult.CREATED;
    }

    // ------------------------------------------------------------------ modificación

    private ImportResult update(Reservation r, Booking b) {
        if (r.getStatus() == ReservationStatus.CANCELLED) {
            note(r, "Beds24 indica la reserva como confirmada, pero está cancelada en el PMS: revísela.");
            return ImportResult.NEEDS_REVIEW;
        }
        if (r.getStatus() != ReservationStatus.CONFIRMED && r.getStatus() != ReservationStatus.CHECKED_IN) {
            return ImportResult.UNCHANGED;
        }
        if (b.roomId() != null && !b.roomId().equals(r.getRoomType().getBeds24RoomId())) {
            note(r, "La OTA cambió la tipología (habitación Beds24 " + b.roomId() + "): cambie la reserva manualmente.");
            return ImportResult.NEEDS_REVIEW;
        }
        if (!validDates(b)) {
            log.warn("Beds24: modificación de {} con fechas no válidas; se ignora", b.id());
            return ImportResult.IGNORED;
        }
        LocalDate checkIn = b.arrival();
        LocalDate checkOut = b.departure();
        int adults = adults(b);
        int children = children(b);
        BigDecimal total = money(b.price());
        BigDecimal commission = money(b.commission());

        boolean datesChanged = !checkIn.equals(r.getCheckInDate()) || !checkOut.equals(r.getCheckOutDate());
        boolean stayChanged = datesChanged || adults != r.getAdults() || children != r.getChildren()
                || total.compareTo(r.getTotalAmount()) != 0;
        boolean commissionChanged = commission.compareTo(r.getCommissionAmount()) != 0;
        if (!stayChanged) {
            if (commissionChanged) {
                r.updateCommission(commission);
                reservationRepository.flush();
                return ImportResult.UPDATED;
            }
            return ImportResult.UNCHANGED;
        }
        if (r.getStatus() == ReservationStatus.CHECKED_IN && !checkIn.equals(r.getCheckInDate())) {
            note(r, "La OTA cambió la llegada (" + checkIn + ") con el huésped ya alojado: no se ha aplicado.");
            return ImportResult.NEEDS_REVIEW;
        }

        LocalDate oldIn = r.getCheckInDate();
        LocalDate oldOut = r.getCheckOutDate();
        String oldRoom = r.getRoom() == null ? "-" : r.getRoom().getNumber();
        String syncNote = null;
        if (datesChanged) {
            boolean currentRoomFree = r.getRoom() != null
                    && inventoryRepository.roomFreeForStayExcluding(r.getRoom().getId(), checkIn, checkOut, r.getId());
            if (r.getStatus() == ReservationStatus.CHECKED_IN && !currentRoomFree) {
                note(r, "La OTA amplió la estancia hasta el " + checkOut
                        + " pero la unidad está ocupada o bloqueada: no se ha aplicado.");
                return ImportResult.NEEDS_REVIEW;
            }
            if (!currentRoomFree) {
                if (r.getRoom() != null) {
                    r.unassignRoom();
                }
                Optional<Room> replacement = chooseRoom(r.getRoomType(), b.unitId(), checkIn, checkOut, r.getId());
                replacement.ifPresent(r::assignRoom);
                if (replacement.isEmpty()) {
                    syncNote = "Fechas cambiadas por la OTA sin unidad libre: asigne una desde el rack (posible sobreventa).";
                }
            }
        }
        r.changeStay(checkIn, checkOut, adults, children, total, StayPricer.distribute(total, checkIn, checkOut,
                r.getRatePlan()));
        r.updateCommission(commission);
        r.recordChannelSyncNote(syncNote);
        reservationRepository.saveAndFlush(r);

        auditService.record(ActorType.WEBHOOK, null, "reservation.channel_modified", "reservation", r.getId(), Map.of(
                "before", Map.of("checkIn", oldIn.toString(), "checkOut", oldOut.toString(), "room", oldRoom),
                "after", Map.of("checkIn", checkIn.toString(), "checkOut", checkOut.toString(),
                        "room", r.getRoom() == null ? "-" : r.getRoom().getNumber(), "total", total)));
        if (datesChanged) {
            // Las noches liberadas del rango anterior también cambian de disponibilidad.
            events.publishEvent(InventoryChangedEvent.nights(r.getRoomType().getId(), oldIn, oldOut));
        }
        events.publishEvent(new ReservationLifecycleEvent(r.getId(), ReservationLifecycleEvent.Type.STAY_CHANGED));
        return ImportResult.UPDATED;
    }

    // ------------------------------------------------------------------ cancelación

    private ImportResult cancel(Reservation r, Booking b) {
        switch (r.getStatus()) {
            case CANCELLED -> {
                return ImportResult.UNCHANGED;
            }
            case CONFIRMED, PENDING_PAYMENT -> {
                r.cancel("Cancelada en " + channelLabel(b), clock.instant());
                guestAccessService.revokeAll(r.getId());
                reservationRepository.flush();
                auditService.record(ActorType.WEBHOOK, null, "reservation.cancelled_channel", "reservation", r.getId(),
                        Map.of("beds24BookingId", b.id(), "channel", r.getChannel().name()));
                events.publishEvent(new ReservationLifecycleEvent(r.getId(), ReservationLifecycleEvent.Type.CANCELLED));
                return ImportResult.CANCELLED;
            }
            default -> {
                note(r, "La OTA ha cancelado la reserva, que está " + r.getStatus() + " en el PMS: revísela.");
                return ImportResult.NEEDS_REVIEW;
            }
        }
    }

    // ------------------------------------------------------------------ internos

    private RoomType mappedRoomType(Booking b) {
        if (b.roomId() == null) {
            throw new Beds24MappingException("La reserva " + b.id() + " de Beds24 no indica habitación");
        }
        return roomTypeRepository.findByBeds24RoomId(b.roomId()).orElseThrow(() -> new Beds24MappingException(
                "La habitación " + b.roomId() + " de Beds24 no está vinculada a ninguna tipología (reserva " + b.id() + ")"));
    }

    /** Unidad indicada por Beds24 si está libre; si no, la primera libre de la tipología. */
    private Optional<Room> chooseRoom(RoomType roomType, Long unitId, LocalDate checkIn, LocalDate checkOut,
                                      UUID reservationId) {
        if (unitId != null && unitId > 0 && unitId <= Integer.MAX_VALUE) {
            Optional<Room> preferred = roomRepository.findByRoomTypeIdAndBeds24UnitId(roomType.getId(), unitId.intValue())
                    .filter(room -> reservationId == null
                            ? inventoryRepository.freeRoomIdsForStay(roomType.getId(), checkIn, checkOut).contains(room.getId())
                            : inventoryRepository.roomFreeForStayExcluding(room.getId(), checkIn, checkOut, reservationId));
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return inventoryRepository.freeRoomIdsForStay(roomType.getId(), checkIn, checkOut).stream()
                .findFirst()
                .map(roomRepository::getReferenceById);
    }

    private Optional<RatePlan> defaultPlan(RoomType roomType) {
        List<RatePlan> plans = ratePlanRepository.findByRoomTypeIdInAndActiveTrue(List.of(roomType.getId()));
        return plans.stream().filter(RatePlan::isDefaultPlan).findFirst()
                .or(() -> plans.stream().min(Comparator.comparing(RatePlan::getCode)));
    }

    private void note(Reservation r, String message) {
        if (!message.equals(r.getChannelSyncNote())) {
            r.recordChannelSyncNote(message);
            reservationRepository.flush();
            auditService.record(ActorType.WEBHOOK, null, "reservation.channel_review", "reservation", r.getId(),
                    Map.of("note", message));
            log.warn("Reserva {}: {}", r.getCode(), message);
        }
    }

    private Guest newGuest(Booking b) {
        String firstName = truncate(blankToNull(b.firstName()), 100);
        String lastName = truncate(blankToNull(b.lastName()), 100);
        Guest guest = new Guest(firstName == null ? "Huésped" : firstName, lastName == null ? "-" : lastName, null);
        String email = blankToNull(b.email());
        if (email != null && email.length() <= 254 && EMAIL.matcher(email).matches()) {
            guest.setEmail(email);
        }
        guest.setPhone(truncate(blankToNull(b.mobile() != null && !b.mobile().isBlank() ? b.mobile() : b.phone()), 30));
        guest.setCountryCode(iso3Country(b.country()));
        guest.setPreferredLocale(b.lang() != null && b.lang().trim().toLowerCase(Locale.ROOT).startsWith("es") ? "es" : "en");
        return guest;
    }

    ReservationChannel channelOf(Booking b) {
        return mapChannel(channelLabel(b));
    }

    static ReservationChannel mapChannel(String label) {
        String c = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (c.contains("booking")) {
            return ReservationChannel.BOOKING_COM;
        }
        if (c.contains("airbnb")) {
            return ReservationChannel.AIRBNB;
        }
        if (c.contains("expedia") || c.contains("hotels.com")) {
            return ReservationChannel.EXPEDIA;
        }
        if (c.contains("google")) {
            return ReservationChannel.GOOGLE;
        }
        return ReservationChannel.BEDS24_OTHER;
    }

    private PaymentMode paymentModeOf(Booking b) {
        String label = channelLabel(b).toLowerCase(Locale.ROOT);
        return properties.otaCollectChannels().stream().anyMatch(c -> label.contains(c.toLowerCase(Locale.ROOT)))
                ? PaymentMode.OTA_COLLECT : PaymentMode.AT_PROPERTY;
    }

    private static String channelLabel(Booking b) {
        return notBlank(b.channel()) ? b.channel().trim() : notBlank(b.apiSource()) ? b.apiSource().trim() : "Beds24";
    }

    static String iso3Country(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.matches("^[A-Z]{3}$")) {
            return v;
        }
        if (v.matches("^[A-Z]{2}$")) {
            try {
                String iso3 = Locale.of("", v).getISO3Country();
                return iso3.isEmpty() ? null : iso3;
            } catch (MissingResourceException ex) {
                return null;
            }
        }
        return null;
    }

    private static boolean validDates(Booking b) {
        return b.arrival() != null && b.departure() != null && b.departure().isAfter(b.arrival())
                && !b.departure().isAfter(b.arrival().plusDays(730));
    }

    private static int adults(Booking b) {
        return b.numAdult() == null || b.numAdult() < 1 ? 1 : Math.min(b.numAdult(), 99);
    }

    private static int children(Booking b) {
        return b.numChild() == null || b.numChild() < 0 ? 0 : Math.min(b.numChild(), 99);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (notBlank(p)) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public enum ImportResult {
        CREATED, UPDATED, CANCELLED, UNCHANGED,
        /** Cambio no aplicable automáticamente: anotado en la reserva para recepción. */
        NEEDS_REVIEW,
        /** Reserva propia devuelta por Beds24 (solo se guarda su identificador). */
        OWN_ECHO,
        /** Estado que no ocupa inventario (petición, consulta, bloqueo) o datos no válidos. */
        IGNORED
    }
}
