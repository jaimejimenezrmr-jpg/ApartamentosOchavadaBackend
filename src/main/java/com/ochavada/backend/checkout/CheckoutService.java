package com.ochavada.backend.checkout;

import com.ochavada.backend.availability.InventoryChangedEvent;
import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.auth.TokenHasher;
import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.availability.StayQuoteService;
import com.ochavada.backend.availability.StayRequestValidator;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.checkout.HoldReleaseService.ReleaseOutcome;
import com.ochavada.backend.checkout.dto.CheckoutRequest;
import com.ochavada.backend.checkout.dto.CheckoutResponse;
import com.ochavada.backend.checkout.dto.CheckoutResponse.PaymentInstructions;
import com.ochavada.backend.checkout.dto.CheckoutResponse.StaySummary;
import com.ochavada.backend.checkout.dto.CheckoutStatusResponse;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.guest.GuestRepository;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxService;
import com.ochavada.backend.notification.ReservationEmailHandler;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentProvider;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.payment.StripeProperties;
import com.ochavada.backend.payment.gateway.PaymentGateway;
import com.ochavada.backend.payment.gateway.PaymentGateway.CreatedIntent;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RatePlanRepository;
import com.ochavada.backend.rates.pricing.DayRate;
import com.ochavada.backend.rates.pricing.StayQuote;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationCodeGenerator;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout en dos pasos de la web propia.
 * <ol>
 *     <li><b>Retención</b> ({@link #start}): se recalcula el precio, se asigna una unidad libre y se crea la
 *     reserva {@code PENDING_PAYMENT}. La restricción de exclusión de PostgreSQL resuelve las carreras entre
 *     dos clientes que eligen la misma unidad. Después se crea el PaymentIntent de Stripe.</li>
 *     <li><b>Pago</b>: la web confirma el pago con Stripe Elements; el webhook {@code payment_intent.succeeded}
 *     confirma la reserva. La web consulta {@link #status} mientras tanto.</li>
 * </ol>
 * Las llamadas a Stripe se hacen siempre fuera de las transacciones de base de datos.
 */
@Slf4j
@Service
public class CheckoutService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final String OVERLAP_CONSTRAINT = "ex_reservation_room_overlap";
    private static final String IDEMPOTENCY_INDEX = "ux_reservation_idempotency_key";

    private final RatePlanRepository ratePlanRepository;
    private final GuestRepository guestRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryRepository inventoryRepository;
    private final StayQuoteService quoteService;
    private final StayRequestValidator stayValidator;
    private final ReservationCodeGenerator codeGenerator;
    private final TokenHasher tokenHasher;
    private final PaymentGateway paymentGateway;
    private final HoldReleaseService holdReleaseService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final StripeProperties stripeProperties;
    private final CheckoutProperties checkoutProperties;
    private final PropertyCalendar calendar;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public CheckoutService(RatePlanRepository ratePlanRepository, GuestRepository guestRepository,
                           ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                           InventoryRepository inventoryRepository, StayQuoteService quoteService,
                           StayRequestValidator stayValidator, ReservationCodeGenerator codeGenerator,
                           TokenHasher tokenHasher, PaymentGateway paymentGateway,
                           HoldReleaseService holdReleaseService, OutboxService outboxService,
                           AuditService auditService, StripeProperties stripeProperties,
                           CheckoutProperties checkoutProperties, PropertyCalendar calendar,
                           EntityManager entityManager, ApplicationEventPublisher events,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.events = events;
        this.ratePlanRepository = ratePlanRepository;
        this.guestRepository = guestRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.inventoryRepository = inventoryRepository;
        this.quoteService = quoteService;
        this.stayValidator = stayValidator;
        this.codeGenerator = codeGenerator;
        this.tokenHasher = tokenHasher;
        this.paymentGateway = paymentGateway;
        this.holdReleaseService = holdReleaseService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.stripeProperties = stripeProperties;
        this.checkoutProperties = checkoutProperties;
        this.calendar = calendar;
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ paso 1

    public CheckoutResponse start(CheckoutRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<UUID> existing = readTx.execute(s ->
                    reservationRepository.findByIdempotencyKey(idempotencyKey).map(Reservation::getId));
            if (existing.isPresent()) {
                return resume(existing.get(), request);
            }
        }
        stayValidator.validate(request.checkIn(), request.checkOut());
        Offer offer = Objects.requireNonNull(readTx.execute(s -> prepareOffer(request)));

        String checkoutToken = tokenHasher.generateToken();
        UUID reservationId = null;
        for (UUID roomId : offer.candidateRoomIds()) {
            try {
                reservationId = tx.execute(s -> createHold(request, offer, roomId, checkoutToken, idempotencyKey));
                break;
            } catch (DataIntegrityViolationException ex) {
                String violated = String.valueOf(ex.getMostSpecificCause().getMessage());
                if (violated.contains(OVERLAP_CONSTRAINT)) {
                    log.info("La unidad {} se ocupó mientras se retenía; se prueba la siguiente", roomId);
                    continue;
                }
                if (idempotencyKey != null && violated.contains(IDEMPOTENCY_INDEX)) {
                    UUID concurrent = readTx.execute(s -> reservationRepository.findByIdempotencyKey(idempotencyKey)
                            .map(Reservation::getId).orElseThrow());
                    return resume(concurrent, request);
                }
                throw ex;
            }
        }
        if (reservationId == null) {
            throw soldOut();
        }
        final UUID heldId = reservationId;

        StayQuote quote = offer.quote();
        if (quote.prepaymentAmount().signum() == 0) {
            tx.executeWithoutResult(s -> confirmWithoutPrepayment(heldId));
            return buildResponse(heldId, checkoutToken, null);
        }

        String code = readTx.execute(s -> reservationRepository.findById(heldId).map(Reservation::getCode).orElseThrow());
        return createPaymentAndRespond(heldId, checkoutToken, code, quote.prepaymentAmount(), offer.currency(),
                request.guest().email());
    }

    // ------------------------------------------------------------------ estado y cancelación

    public CheckoutStatusResponse status(String code, String checkoutToken) {
        return readTx.execute(s -> {
            Reservation r = verified(code, checkoutToken);
            Optional<Payment> payment = latestStripePayment(r.getId());
            return new CheckoutStatusResponse(r.getCode(), r.getStatus(),
                    payment.map(Payment::getStatus).orElse(null),
                    payment.map(Payment::getFailureMessage).orElse(null),
                    r.getHoldExpiresAt(), r.getTotalAmount(), r.getPaidAmount(), r.getCurrency());
        });
    }

    public void cancel(String code, String checkoutToken) {
        Reservation r = readTx.execute(s -> verified(code, checkoutToken));
        ReleaseOutcome outcome = holdReleaseService.release(r.getId(), "Abandonado por el cliente", false);
        switch (outcome) {
            case RELEASED -> log.info("Checkout {} abandonado por el cliente", code);
            case PAYMENT_IN_PROGRESS -> throw new ConflictException("checkout.payment_in_progress",
                    "El pago ya se está procesando y no puede cancelarse desde aquí");
            case NOT_PENDING -> {
                ReservationStatus current = readTx.execute(s ->
                        reservationRepository.findById(r.getId()).orElseThrow().getStatus());
                if (current != ReservationStatus.CANCELLED) {
                    throw new ConflictException("checkout.not_cancellable",
                            "La reserva ya está confirmada; para anularla contacte con el alojamiento");
                }
            }
        }
    }

    // ------------------------------------------------------------------ internos: retención

    private Offer prepareOffer(CheckoutRequest request) {
        RatePlan plan = ratePlanRepository.findById(request.ratePlanId())
                .filter(p -> p.isActive() && p.getRoomType().isActive())
                .orElseThrow(() -> new BusinessRuleException("checkout.rate_unavailable",
                        "La tarifa seleccionada ya no está disponible"));
        RoomType roomType = plan.getRoomType();
        if (!roomType.accommodates(request.adults(), request.children())) {
            throw new BusinessRuleException("checkout.occupancy_exceeded",
                    "El alojamiento admite como máximo " + roomType.getMaxAdults() + " adultos, "
                            + roomType.getMaxChildren() + " niños y " + roomType.getMaxOccupancy() + " personas");
        }
        StayQuote quote = quoteService.quote(List.of(plan), request.checkIn(), request.checkOut()).get(plan.getId());
        if (!quote.bookable()) {
            throw new BusinessRuleException("checkout.rate_not_bookable",
                    "La tarifa no admite esta estancia", Map.of("restrictions", quote.violations()));
        }
        int units = inventoryRepository.availableUnitsForStay(List.of(roomType.getId()), request.checkIn(),
                request.checkOut()).getOrDefault(roomType.getId(), 0);
        List<UUID> candidates = units > 0
                ? inventoryRepository.freeRoomIdsForStay(roomType.getId(), request.checkIn(), request.checkOut())
                : List.of();
        if (candidates.isEmpty()) {
            throw soldOut();
        }
        return new Offer(plan.getId(), roomType.getId(), roomType.getCurrency(), quote, candidates);
    }

    private UUID createHold(CheckoutRequest request, Offer offer, UUID roomId, String checkoutToken,
                            String idempotencyKey) {
        Instant now = clock.instant();
        CheckoutRequest.GuestDetails g = request.guest();
        Guest guest = new Guest(g.firstName().trim(), g.lastName1().trim(), blankToNull(g.lastName2()));
        guest.setEmail(g.email());
        guest.setPhone(g.phone().trim());
        guest.setCountryCode(g.countryCode());
        guest.setPreferredLocale(request.localeOrDefault());
        guest.setMarketingConsent(Boolean.TRUE.equals(request.marketingConsent()));
        guest.setPrivacyAcceptedAt(now);
        guestRepository.save(guest);

        Reservation reservation = Reservation.directHold(
                codeGenerator.nextCode(),
                entityManager.getReference(RoomType.class, offer.roomTypeId()),
                entityManager.getReference(Room.class, roomId),
                entityManager.getReference(RatePlan.class, offer.ratePlanId()),
                guest, request.checkIn(), request.checkOut(), request.adults(), request.children(),
                offer.quote().totalAmount(), offer.currency(), now.plus(checkoutProperties.holdDuration()), now,
                tokenHasher.hash(checkoutToken), idempotencyKey, request.localeOrDefault());
        for (DayRate night : offer.quote().nights()) {
            reservation.addNight(night.date(), night.price());
        }
        reservation.setSpecialRequests(blankToNull(request.specialRequests()));
        reservation.setExpectedArrivalTime(request.expectedArrivalTime());
        reservationRepository.saveAndFlush(reservation);
        // La retención ya ocupa inventario: se retira de las OTAs mientras el huésped paga.
        events.publishEvent(InventoryChangedEvent.nights(offer.roomTypeId(), request.checkIn(), request.checkOut()));
        auditService.record(ActorType.GUEST, null, "reservation.hold_created", "reservation", reservation.getId(),
                Map.of("code", reservation.getCode(), "total", offer.quote().totalAmount(),
                        "prepayment", offer.quote().prepaymentAmount()));
        return reservation.getId();
    }

    private void confirmWithoutPrepayment(UUID reservationId) {
        Reservation reservation = reservationRepository.findForUpdate(reservationId).orElseThrow();
        reservation.confirmWithoutPrepayment();
        outboxService.enqueue(OutboxDestination.EMAIL, "reservation", reservationId,
                ReservationEmailHandler.RESERVATION_CONFIRMED, Map.of("reservationId", reservationId));
        auditService.record(ActorType.GUEST, null, "reservation.confirmed", "reservation", reservationId,
                Map.of("code", reservation.getCode(), "paymentMode", reservation.getPaymentMode().name()));
        events.publishEvent(new ReservationLifecycleEvent(reservationId, ReservationLifecycleEvent.Type.CONFIRMED));
    }

    private CheckoutResponse createPaymentAndRespond(UUID reservationId, String checkoutToken, String code,
                                                     BigDecimal prepayment, String currency, String email) {
        CreatedIntent intent;
        try {
            intent = paymentGateway.createIntent(prepayment, currency, "Reserva " + code, email,
                    Map.of("reservationId", reservationId.toString(), "reservationCode", code),
                    "checkout-" + reservationId);
        } catch (RuntimeException ex) {
            holdReleaseService.release(reservationId, "Error al iniciar el pago", false);
            throw ex;
        }
        tx.executeWithoutResult(s -> paymentRepository.save(Payment.stripe(
                reservationRepository.getReferenceById(reservationId), prepayment, currency, intent.paymentIntentId())));
        return buildResponse(reservationId, checkoutToken, intent.clientSecret());
    }

    // ------------------------------------------------------------------ internos: reanudación idempotente

    private CheckoutResponse resume(UUID reservationId, CheckoutRequest request) {
        String newToken = tokenHasher.generateToken();
        ResumeState state = Objects.requireNonNull(tx.execute(s -> {
            Reservation r = reservationRepository.findForUpdate(reservationId).orElseThrow();
            boolean sameRequest = r.getRatePlan() != null && r.getRatePlan().getId().equals(request.ratePlanId())
                    && r.getCheckInDate().equals(request.checkIn()) && r.getCheckOutDate().equals(request.checkOut())
                    && r.getAdults() == request.adults() && r.getChildren() == request.children()
                    && r.getHolder().getEmail() != null
                    && r.getHolder().getEmail().equalsIgnoreCase(request.guest().email());
            if (!sameRequest) {
                throw new ConflictException("checkout.idempotency_key_reused",
                        "La clave de idempotencia ya se usó para otra reserva distinta");
            }
            if (r.getStatus() == ReservationStatus.CANCELLED) {
                throw new ConflictException("checkout.expired",
                        "La retención de esta reserva ha caducado. Inicie una nueva búsqueda");
            }
            r.rotateCheckoutToken(tokenHasher.hash(newToken));
            String intentId = latestStripePayment(reservationId).map(Payment::getStripePaymentIntentId).orElse(null);
            BigDecimal prepayment = prepaymentFor(r);
            return new ResumeState(r.getStatus(), r.getCode(), intentId, prepayment, r.getCurrency(),
                    r.getHolder().getEmail());
        }));

        if (state.status() != ReservationStatus.PENDING_PAYMENT || state.prepayment().signum() == 0) {
            return buildResponse(reservationId, newToken, null);
        }
        if (state.paymentIntentId() == null) {
            // El intento anterior creó la retención pero no llegó a registrar el pago.
            return createPaymentAndRespond(reservationId, newToken, state.code(), state.prepayment(),
                    state.currency(), state.email());
        }
        String clientSecret = paymentGateway.retrieveIntent(state.paymentIntentId()).clientSecret();
        return buildResponse(reservationId, newToken, clientSecret);
    }

    // ------------------------------------------------------------------ internos: respuesta

    private CheckoutResponse buildResponse(UUID reservationId, String checkoutToken, String clientSecret) {
        return readTx.execute(s -> {
            Reservation r = reservationRepository.findById(reservationId).orElseThrow();
            RatePlan plan = r.getRatePlan();
            BigDecimal prepayment = prepaymentFor(r);
            StaySummary stay = new StaySummary(r.getRoomType().getName(), r.getRoomType().getSlug(),
                    plan != null ? plan.getName() : null, r.getCheckInDate(), r.getCheckOutDate(), r.nightCount(),
                    r.getAdults(), r.getChildren());
            return new CheckoutResponse(r.getCode(), r.getStatus(), checkoutToken, r.getHoldExpiresAt(),
                    r.getCurrency(), r.getTotalAmount(), prepayment,
                    r.getTotalAmount().subtract(prepayment).max(BigDecimal.ZERO),
                    freeCancellationUntil(r), stay,
                    clientSecret == null ? null
                            : new PaymentInstructions("STRIPE", stripeProperties.publishableKey(), clientSecret));
        });
    }

    private BigDecimal prepaymentFor(Reservation r) {
        RatePlan plan = r.getRatePlan();
        if (plan == null) {
            return BigDecimal.ZERO;
        }
        return r.getTotalAmount().multiply(plan.getPrepaymentPercent()).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private LocalDate freeCancellationUntil(Reservation r) {
        RatePlan plan = r.getRatePlan();
        if (plan == null || !plan.isRefundable() || plan.getFreeCancellationDays() == null) {
            return null;
        }
        LocalDate deadline = r.getCheckInDate().minusDays(plan.getFreeCancellationDays());
        return deadline.isBefore(calendar.today()) ? null : deadline;
    }

    private Optional<Payment> latestStripePayment(UUID reservationId) {
        return paymentRepository.findByReservationIdOrderByCreatedAtAsc(reservationId).stream()
                .filter(p -> p.getProvider() == PaymentProvider.STRIPE)
                .max(Comparator.comparing(Payment::getCreatedAt));
    }

    /** Busca la reserva y comprueba el secreto en tiempo constante. 404 si no coincide (sin oráculo). */
    private Reservation verified(String code, String checkoutToken) {
        Reservation r = reservationRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", code));
        if (checkoutToken == null || r.getCheckoutTokenHash() == null
                || !MessageDigest.isEqual(tokenHasher.hash(checkoutToken).getBytes(StandardCharsets.US_ASCII),
                r.getCheckoutTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ResourceNotFoundException("reserva", code);
        }
        return r;
    }

    private static ConflictException soldOut() {
        return new ConflictException("checkout.sold_out",
                "Ya no quedan unidades disponibles para esas fechas. Pruebe otras fechas u otro alojamiento");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Offer(UUID ratePlanId, UUID roomTypeId, String currency, StayQuote quote,
                         List<UUID> candidateRoomIds) {
    }

    private record ResumeState(ReservationStatus status, String code, String paymentIntentId, BigDecimal prepayment,
                               String currency, String email) {
    }
}
