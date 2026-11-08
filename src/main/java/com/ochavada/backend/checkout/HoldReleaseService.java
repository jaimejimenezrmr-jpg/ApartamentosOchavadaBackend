package com.ochavada.backend.checkout;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.guestportal.GuestAccessService;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentProvider;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.payment.gateway.PaymentGateway;
import com.ochavada.backend.payment.gateway.PaymentGateway.IntentSnapshot;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Libera la retención de una reserva pendiente de pago.
 * <p>
 * Orden deliberado: primero se cancela el PaymentIntent en Stripe (fuera de transacción) y solo si
 * Stripe confirma que no hay cobro en curso se libera la unidad. Si el pago ya se ha cobrado o se está
 * procesando, la retención se amplía y el webhook confirmará la reserva: así nunca se libera una unidad
 * que el huésped acaba de pagar.
 */
@Slf4j
@Service
public class HoldReleaseService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final GuestAccessService guestAccessService;
    private final AuditService auditService;
    private final CheckoutProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public HoldReleaseService(ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                              PaymentGateway paymentGateway, GuestAccessService guestAccessService,
                              AuditService auditService, CheckoutProperties properties,
                              PlatformTransactionManager transactionManager, Clock clock,
                              ApplicationEventPublisher events) {
        this.events = events;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.guestAccessService = guestAccessService;
        this.auditService = auditService;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param onlyIfExpired si es {@code true} (caducidad automática) no se libera una retención que se
     *                      haya ampliado entretanto
     */
    public ReleaseOutcome release(UUID reservationId, String reason, boolean onlyIfExpired) {
        List<String> openIntents = tx.execute(status -> {
            Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
            if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
                return null;
            }
            return paymentRepository.findByReservationIdOrderByCreatedAtAsc(reservationId).stream()
                    .filter(p -> p.getProvider() == PaymentProvider.STRIPE && !p.getStatus().isFinal())
                    .map(Payment::getStripePaymentIntentId)
                    .toList();
        });
        if (openIntents == null) {
            return ReleaseOutcome.NOT_PENDING;
        }

        for (String intentId : openIntents) {
            IntentSnapshot snapshot = paymentGateway.cancelIntent(intentId);
            if (snapshot.status().isPaidOrInFlight()) {
                tx.executeWithoutResult(status -> {
                    Reservation reservation = reservationRepository.findForUpdate(reservationId).orElseThrow();
                    if (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT) {
                        Instant extended = clock.instant().plus(properties.inFlightHoldExtension());
                        reservation.extendHold(extended);
                        auditService.record(ActorType.SYSTEM, null, "reservation.hold_extended", "reservation",
                                reservationId, Map.of("paymentIntentId", intentId, "until", extended.toString(),
                                        "intentStatus", snapshot.status().name()));
                    }
                });
                log.info("Retención de {} ampliada: el pago {} está {}", reservationId, intentId, snapshot.status());
                return ReleaseOutcome.PAYMENT_IN_PROGRESS;
            }
        }

        return tx.execute(status -> {
            Instant now = clock.instant();
            Reservation reservation = reservationRepository.findForUpdate(reservationId).orElseThrow();
            if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT
                    || (onlyIfExpired && !reservation.isHoldExpiredAt(now))) {
                return ReleaseOutcome.NOT_PENDING;
            }
            paymentRepository.findByReservationIdOrderByCreatedAtAsc(reservationId).forEach(Payment::markCancelled);
            reservation.cancel(reason, now);
            guestAccessService.revokeAll(reservationId);
            events.publishEvent(InventoryChangedEvent.nights(reservation.getRoomType().getId(),
                    reservation.getCheckInDate(), reservation.getCheckOutDate()));
            auditService.record(ActorType.SYSTEM, null, "reservation.hold_released", "reservation", reservationId,
                    Map.of("code", reservation.getCode(), "reason", reason));
            return ReleaseOutcome.RELEASED;
        });
    }

    public enum ReleaseOutcome {
        /** Reserva cancelada y unidad liberada. */
        RELEASED,
        /** Hay un cobro hecho o en curso: no se libera (se amplía la retención). */
        PAYMENT_IN_PROGRESS,
        /** La reserva ya no estaba pendiente de pago (confirmada, cancelada o retención ampliada). */
        NOT_PENDING
    }
}
