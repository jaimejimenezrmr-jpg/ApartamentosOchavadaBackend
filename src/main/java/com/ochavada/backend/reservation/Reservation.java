package com.ochavada.backend.reservation;

import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.rates.RatePlan;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Reserva. Las fechas forman el intervalo semiabierto {@code [checkInDate, checkOutDate)}.
 * Las transiciones de estado se hacen solo a través de los métodos de dominio.
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends VersionedEntity {

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private ReservationChannel channel;

    @Setter
    @Column(name = "beds24_booking_id")
    private Long beds24BookingId;

    @Setter
    @Column(name = "channel_booking_reference", length = 100)
    private String channelBookingReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_plan_id")
    private RatePlan ratePlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "holder_guest_id", nullable = false)
    private Guest holder;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    /** Columna generada por PostgreSQL ({@code check_out_date - check_in_date}). */
    @Column(name = "nights", insertable = false, updatable = false)
    private Integer nights;

    @Setter
    @Column(name = "expected_arrival_time")
    private LocalTime expectedArrivalTime;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "adults", nullable = false)
    private int adults;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "children", nullable = false)
    private int children;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "commission_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal commissionAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode;

    @Setter
    @Column(name = "special_requests", columnDefinition = "text")
    private String specialRequests;

    @Setter
    @Column(name = "internal_notes", columnDefinition = "text")
    private String internalNotes;

    @Setter
    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "online_checkin_completed_at")
    private Instant onlineCheckinCompletedAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "checkout_token_hash", length = 64)
    private String checkoutTokenHash;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    /** Aviso de la última sincronización con el channel manager (p. ej. cambio no aplicable). */
    @Column(name = "channel_sync_note", length = 500)
    private String channelSyncNote;

    @ElementCollection
    @CollectionTable(name = "reservation_night", joinColumns = @JoinColumn(name = "reservation_id"))
    @OrderBy("stayDate ASC")
    private List<NightCharge> nightCharges = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "reservation_guest", joinColumns = @JoinColumn(name = "reservation_id"))
    private List<ReservationOccupant> occupants = new ArrayList<>();

    /**
     * Reserva de la web propia retenida a la espera del pago.
     */
    public static Reservation directHold(String code, RoomType roomType, Room room, RatePlan ratePlan, Guest holder,
                                         LocalDate checkIn, LocalDate checkOut, int adults, int children,
                                         BigDecimal totalAmount, String currency, Instant holdExpiresAt,
                                         Instant termsAcceptedAt, String checkoutTokenHash, String idempotencyKey,
                                         String locale) {
        Reservation r = new Reservation();
        r.code = Objects.requireNonNull(code, "code");
        r.status = ReservationStatus.PENDING_PAYMENT;
        r.channel = ReservationChannel.DIRECT;
        r.roomType = Objects.requireNonNull(roomType, "roomType");
        r.room = Objects.requireNonNull(room, "room");
        r.ratePlan = Objects.requireNonNull(ratePlan, "ratePlan");
        r.holder = Objects.requireNonNull(holder, "holder");
        r.checkInDate = checkIn;
        r.checkOutDate = checkOut;
        r.adults = adults;
        r.children = children;
        r.totalAmount = totalAmount;
        r.paidAmount = BigDecimal.ZERO;
        r.commissionAmount = BigDecimal.ZERO;
        r.currency = currency;
        r.paymentMode = PaymentMode.ONLINE;
        r.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        r.termsAcceptedAt = Objects.requireNonNull(termsAcceptedAt, "termsAcceptedAt");
        r.checkoutTokenHash = checkoutTokenHash;
        r.idempotencyKey = idempotencyKey;
        r.locale = locale;
        r.occupants.add(new ReservationOccupant(holder.getId(), true, null, termsAcceptedAt));
        return r;
    }

    /**
     * Reserva introducida por el personal (teléfono, mostrador, OTA sin channel manager...). Nace confirmada.
     *
     * @param room unidad asignada; puede ser {@code null} y asignarse más tarde desde el rack
     */
    public static Reservation manual(String code, ReservationChannel channel, RoomType roomType, Room room,
                                     RatePlan ratePlan, Guest holder, LocalDate checkIn, LocalDate checkOut,
                                     int adults, int children, BigDecimal totalAmount, String currency,
                                     PaymentMode paymentMode, UUID createdByUserId, String locale, Instant now) {
        if (channel == ReservationChannel.DIRECT) {
            throw new IllegalArgumentException("El canal DIRECT está reservado a la web propia");
        }
        Reservation r = new Reservation();
        r.code = Objects.requireNonNull(code, "code");
        r.status = ReservationStatus.CONFIRMED;
        r.channel = Objects.requireNonNull(channel, "channel");
        r.roomType = Objects.requireNonNull(roomType, "roomType");
        r.room = room;
        r.ratePlan = ratePlan;
        r.holder = Objects.requireNonNull(holder, "holder");
        r.checkInDate = checkIn;
        r.checkOutDate = checkOut;
        r.adults = adults;
        r.children = children;
        r.totalAmount = totalAmount;
        r.paidAmount = BigDecimal.ZERO;
        r.commissionAmount = BigDecimal.ZERO;
        r.currency = currency;
        r.paymentMode = Objects.requireNonNull(paymentMode, "paymentMode");
        r.createdByUserId = createdByUserId;
        r.locale = locale;
        r.occupants.add(new ReservationOccupant(holder.getId(), true, null, now));
        return r;
    }

    /**
     * Reserva importada del channel manager (Booking.com, Airbnb...). Nace confirmada y nunca se rechaza:
     * la OTA ya la ha aceptado. Si no hay unidad libre queda sin asignar (resta inventario de la tipología).
     */
    public static Reservation fromChannel(String code, ReservationChannel channel, long beds24BookingId,
                                          String channelReference, RoomType roomType, Room room, RatePlan ratePlan,
                                          Guest holder, LocalDate checkIn, LocalDate checkOut, int adults,
                                          int children, BigDecimal totalAmount, BigDecimal commissionAmount,
                                          String currency, PaymentMode paymentMode, String locale, Instant now) {
        if (channel == ReservationChannel.DIRECT || channel == ReservationChannel.PHONE
                || channel == ReservationChannel.WALK_IN) {
            throw new IllegalArgumentException("El canal " + channel + " no procede del channel manager");
        }
        Reservation r = new Reservation();
        r.code = Objects.requireNonNull(code, "code");
        r.status = ReservationStatus.CONFIRMED;
        r.channel = channel;
        r.beds24BookingId = beds24BookingId;
        r.channelBookingReference = channelReference;
        r.roomType = Objects.requireNonNull(roomType, "roomType");
        r.room = room;
        r.ratePlan = ratePlan;
        r.holder = Objects.requireNonNull(holder, "holder");
        r.checkInDate = checkIn;
        r.checkOutDate = checkOut;
        r.adults = adults;
        r.children = children;
        r.totalAmount = totalAmount;
        r.paidAmount = BigDecimal.ZERO;
        r.commissionAmount = commissionAmount;
        r.currency = currency;
        r.paymentMode = Objects.requireNonNull(paymentMode, "paymentMode");
        r.locale = locale;
        r.occupants.add(new ReservationOccupant(holder.getId(), true, null, now));
        return r;
    }

    /** ¿Se creó en este sistema (web, teléfono, mostrador) y no en el channel manager? */
    public boolean isLocallyOriginated() {
        return channel == ReservationChannel.DIRECT || channel == ReservationChannel.PHONE
                || channel == ReservationChannel.WALK_IN;
    }

    public void updateCommission(BigDecimal amount) {
        this.commissionAmount = amount.max(BigDecimal.ZERO);
    }

    /** Desasigna la unidad (la reserva sigue restando inventario de su tipología). */
    public void unassignRoom() {
        if (status != ReservationStatus.CONFIRMED && status != ReservationStatus.PENDING_PAYMENT) {
            throw new ConflictException("reservation.invalid_state",
                    "No se puede desasignar la unidad de una reserva en estado " + status);
        }
        this.room = null;
    }

    public void recordChannelSyncNote(String note) {
        this.channelSyncNote = note == null || note.length() <= 500 ? note : note.substring(0, 500);
    }

    /**
     * Sustituye los acompañantes del titular (check-in online o recepción). El titular se conserva.
     */
    public void replaceCompanions(List<ReservationOccupant> companions) {
        occupants.removeIf(o -> !o.isHolder());
        occupants.addAll(companions);
    }

    /** Fija el parentesco del titular (necesario si es responsable de algún menor). */
    public void setHolderRelationship(String relationship, Instant now) {
        occupants.removeIf(ReservationOccupant::isHolder);
        occupants.add(new ReservationOccupant(holder.getId(), true, relationship, now));
    }

    public void markOnlineCheckinCompleted(Instant now) {
        this.onlineCheckinCompletedAt = now;
    }

    public void clearOnlineCheckin() {
        this.onlineCheckinCompletedAt = null;
    }

    public int guestCount() {
        return adults + children;
    }

    /** Sustituye el desglose por noches (alta o cambio de estancia). */
    public void replaceNights(List<NightCharge> nights) {
        nightCharges.clear();
        nightCharges.addAll(nights);
    }

    public void addNight(LocalDate date, BigDecimal price) {
        nightCharges.add(new NightCharge(date, price, ratePlan != null ? ratePlan.getId() : null));
    }

    public int nightCount() {
        return (int) ChronoUnit.DAYS.between(checkInDate, checkOutDate);
    }

    /** Confirma una reserva directa que no requiere prepago (se cobra en el alojamiento). */
    public void confirmWithoutPrepayment() {
        requireStatus(ReservationStatus.PENDING_PAYMENT);
        this.paymentMode = PaymentMode.AT_PROPERTY;
        this.status = ReservationStatus.CONFIRMED;
        this.holdExpiresAt = null;
    }

    /** Registra un cobro confirmado; si la reserva estaba retenida, pasa a confirmada. */
    public void registerPayment(BigDecimal amount) {
        this.paidAmount = paidAmount.add(amount);
        if (status == ReservationStatus.PENDING_PAYMENT) {
            this.status = ReservationStatus.CONFIRMED;
            this.holdExpiresAt = null;
        }
    }

    /** Amplía la retención (p. ej. si el pago está siendo procesado por el banco). */
    public void extendHold(Instant newExpiry) {
        requireStatus(ReservationStatus.PENDING_PAYMENT);
        this.holdExpiresAt = newExpiry;
    }

    /** Sustituye el secreto del checkout (al reanudar un checkout idempotente se entrega uno nuevo). */
    public void rotateCheckoutToken(String newTokenHash) {
        this.checkoutTokenHash = Objects.requireNonNull(newTokenHash, "newTokenHash");
    }

    public void cancel(String reason, Instant now) {
        if (status == ReservationStatus.CANCELLED) {
            return;
        }
        if (status == ReservationStatus.CHECKED_IN || status == ReservationStatus.CHECKED_OUT
                || status == ReservationStatus.NO_SHOW) {
            throw new ConflictException("reservation.not_cancellable",
                    "Una reserva en estado " + status + " no puede cancelarse");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancellationReason = reason;
    }

    /** Asigna o cambia la unidad (también durante la estancia). La exclusión de BD evita solapamientos. */
    public void assignRoom(Room newRoom) {
        Objects.requireNonNull(newRoom, "newRoom");
        if (!(status == ReservationStatus.PENDING_PAYMENT || status == ReservationStatus.CONFIRMED
                || status == ReservationStatus.CHECKED_IN)) {
            throw new ConflictException("reservation.invalid_state",
                    "No se puede cambiar la unidad de una reserva en estado " + status);
        }
        this.room = newRoom;
    }

    /**
     * Cambia fechas y ocupación. Durante la estancia solo puede modificarse la salida.
     */
    public void changeStay(LocalDate newCheckIn, LocalDate newCheckOut, int newAdults, int newChildren,
                           BigDecimal newTotal, List<NightCharge> newNights) {
        if (status == ReservationStatus.CHECKED_IN) {
            if (!newCheckIn.equals(checkInDate)) {
                throw new ConflictException("reservation.check_in_locked",
                        "El huésped ya ha entrado: solo puede modificarse la fecha de salida");
            }
        } else if (status != ReservationStatus.CONFIRMED) {
            throw new ConflictException("reservation.invalid_state",
                    "No se pueden cambiar las fechas de una reserva en estado " + status);
        }
        this.checkInDate = newCheckIn;
        this.checkOutDate = newCheckOut;
        this.adults = newAdults;
        this.children = newChildren;
        this.totalAmount = newTotal;
        replaceNights(newNights);
    }

    public void checkIn(Instant now) {
        requireStatus(ReservationStatus.CONFIRMED);
        if (room == null) {
            throw new ConflictException("reservation.room_required",
                    "Asigne una unidad a la reserva antes de hacer el check-in");
        }
        this.status = ReservationStatus.CHECKED_IN;
        this.checkedInAt = now;
    }

    /**
     * Check-out. Si el huésped sale antes de lo previsto, la salida se adelanta a {@code today} para
     * liberar el inventario; el importe cobrado y el desglose de noches no se modifican.
     */
    public void checkOut(Instant now, LocalDate today) {
        requireStatus(ReservationStatus.CHECKED_IN);
        if (today.isBefore(checkOutDate) && today.isAfter(checkInDate)) {
            this.checkOutDate = today;
        }
        this.status = ReservationStatus.CHECKED_OUT;
        this.checkedOutAt = now;
    }

    public void markNoShow() {
        requireStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.NO_SHOW;
    }

    /** Descuenta un reembolso del importe cobrado. */
    public void registerRefund(BigDecimal amount) {
        this.paidAmount = paidAmount.subtract(amount).max(BigDecimal.ZERO);
    }

    public BigDecimal balanceDue() {
        return totalAmount.subtract(paidAmount).max(BigDecimal.ZERO);
    }

    public boolean isHoldExpiredAt(Instant now) {
        return status == ReservationStatus.PENDING_PAYMENT && holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    private void requireStatus(ReservationStatus expected) {
        if (status != expected) {
            throw new ConflictException("reservation.invalid_state",
                    "La reserva " + code + " está en estado " + status + " (se esperaba " + expected + ")");
        }
    }
}
