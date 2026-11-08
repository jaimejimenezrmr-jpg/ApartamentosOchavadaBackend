package com.ochavada.backend.payment.webhook;

import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxService;
import com.ochavada.backend.integration.webhook.InboundWebhookEvent;
import com.ochavada.backend.integration.webhook.InboundWebhookEvent.WebhookProvider;
import com.ochavada.backend.integration.webhook.InboundWebhookEventRepository;
import com.ochavada.backend.notification.ReservationEmailHandler;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.payment.gateway.PaymentGateway;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa los webhooks de Stripe de forma idempotente.
 * <ol>
 *     <li>Se verifica la firma (fuera de transacción).</li>
 *     <li>En una transacción se registra el evento y se aplica su efecto. Un evento ya procesado se
 *     ignora: Stripe garantiza entrega "al menos una vez" y puede repetirlo.</li>
 *     <li>Si el efecto falla, la transacción se revierte, el fallo queda registrado aparte y se responde
 *     500 para que Stripe reintente.</li>
 * </ol>
 */
@Slf4j
@Service
public class StripeWebhookService {

    static final String SUCCEEDED = "payment_intent.succeeded";
    static final String FAILED = "payment_intent.payment_failed";
    static final String PROCESSING = "payment_intent.processing";
    static final String REQUIRES_ACTION = "payment_intent.requires_action";
    static final String CANCELED = "payment_intent.canceled";

    /** Un pago en proceso (p. ej. confirmación bancaria) amplía la retención para no perder la unidad. */
    private static final Duration PROCESSING_HOLD_EXTENSION = Duration.ofHours(2);

    private final StripeWebhookParser parser;
    private final InboundWebhookEventRepository eventRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentGateway paymentGateway;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate failureTx;
    private final Clock clock;

    public StripeWebhookService(StripeWebhookParser parser, InboundWebhookEventRepository eventRepository,
                                PaymentRepository paymentRepository, ReservationRepository reservationRepository,
                                PaymentGateway paymentGateway, OutboxService outboxService, AuditService auditService,
                                ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.events = events;
        this.parser = parser;
        this.eventRepository = eventRepository;
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.paymentGateway = paymentGateway;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.failureTx = new TransactionTemplate(transactionManager);
        this.failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public void handle(String payload, String signatureHeader) {
        PaymentWebhookEvent event = parser.parse(payload, signatureHeader);
        try {
            tx.executeWithoutResult(status -> process(event));
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateEvent(ex)) {
                // Dos entregas simultáneas del mismo evento: la otra lo está procesando.
                log.info("Evento de Stripe {} recibido en paralelo; se ignora la entrega duplicada", event.eventId());
                return;
            }
            log.error("Error de integridad procesando el evento de Stripe {} ({})", event.eventId(), event.type(), ex);
            recordFailure(event, ex);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Error procesando el evento de Stripe {} ({})", event.eventId(), event.type(), ex);
            recordFailure(event, ex);
            throw ex;
        }
    }

    private void process(PaymentWebhookEvent event) {
        InboundWebhookEvent stored = eventRepository.findForUpdate(WebhookProvider.STRIPE, event.eventId())
                .orElseGet(() -> eventRepository.saveAndFlush(new InboundWebhookEvent(WebhookProvider.STRIPE,
                        event.eventId(), event.type(), event.rawPayload(), clock.instant())));
        if (stored.isDone()) {
            return;
        }
        boolean applied = switch (event.type()) {
            case SUCCEEDED -> onSucceeded(event);
            case FAILED -> onFailed(event);
            case PROCESSING -> onProcessing(event);
            case REQUIRES_ACTION -> withPayment(event, Payment::markRequiresAction);
            case CANCELED -> withPayment(event, Payment::markCancelled);
            default -> false;
        };
        if (applied) {
            stored.markProcessed(clock.instant());
        } else {
            stored.markIgnored(clock.instant());
        }
    }

    private boolean onSucceeded(PaymentWebhookEvent event) {
        Optional<Payment> found = lockedPayment(event);
        if (found.isEmpty()) {
            return false;
        }
        Payment payment = found.get();
        Reservation reservation = reservationRepository.findForUpdate(payment.getReservation().getId()).orElseThrow();
        if (!payment.markSucceeded(event.chargeId(), clock.instant())) {
            return true;
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            // Cobro tardío de una reserva ya liberada: nunca debería ocurrir porque la expiración cancela
            // antes el PaymentIntent, pero si ocurre se devuelve el dinero.
            paymentGateway.refund(event.paymentIntentId(), payment.getAmount(),
                    "Reserva cancelada antes de completarse el pago", "late-refund-" + event.paymentIntentId());
            payment.markRefunded(payment.getAmount());
            auditService.record(ActorType.WEBHOOK, null, "payment.refunded_late", "reservation", reservation.getId(),
                    Map.of("paymentIntentId", event.paymentIntentId(), "amount", payment.getAmount()));
            log.error("Pago tardío reembolsado para la reserva cancelada {}", reservation.getCode());
            return true;
        }
        ReservationStatus before = reservation.getStatus();
        reservation.registerPayment(payment.getAmount());
        auditService.record(ActorType.WEBHOOK, null, "payment.succeeded", "reservation", reservation.getId(),
                Map.of("paymentIntentId", event.paymentIntentId(), "amount", payment.getAmount()));
        if (before == ReservationStatus.PENDING_PAYMENT && reservation.getStatus() == ReservationStatus.CONFIRMED) {
            outboxService.enqueue(OutboxDestination.EMAIL, "reservation", reservation.getId(),
                    ReservationEmailHandler.RESERVATION_CONFIRMED, Map.of("reservationId", reservation.getId()));
            auditService.record(ActorType.WEBHOOK, null, "reservation.confirmed", "reservation", reservation.getId(),
                    Map.of("code", reservation.getCode()));
            events.publishEvent(new ReservationLifecycleEvent(reservation.getId(), ReservationLifecycleEvent.Type.CONFIRMED));
        }
        return true;
    }

    private boolean onFailed(PaymentWebhookEvent event) {
        // La reserva sigue retenida: el huésped puede reintentar con otra tarjeta hasta que caduque.
        return withPayment(event, p -> p.markFailed(event.failureCode(), event.failureMessage()));
    }

    private boolean onProcessing(PaymentWebhookEvent event) {
        Optional<Payment> payment = lockedPayment(event);
        if (payment.isEmpty()) {
            return false;
        }
        Reservation reservation = reservationRepository.findForUpdate(payment.get().getReservation().getId())
                .orElseThrow();
        if (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT) {
            reservation.extendHold(clock.instant().plus(PROCESSING_HOLD_EXTENSION));
        }
        return true;
    }

    private boolean withPayment(PaymentWebhookEvent event, Consumer<Payment> action) {
        Optional<Payment> payment = lockedPayment(event);
        payment.ifPresent(action);
        return payment.isPresent();
    }

    private Optional<Payment> lockedPayment(PaymentWebhookEvent event) {
        if (event.paymentIntentId() == null) {
            return Optional.empty();
        }
        Optional<Payment> payment = paymentRepository.findForUpdateByIntentId(event.paymentIntentId());
        if (payment.isEmpty()) {
            log.warn("Evento {} de un PaymentIntent desconocido ({}): se ignora", event.type(), event.paymentIntentId());
        }
        return payment;
    }

    private static boolean isDuplicateEvent(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && message.contains("uq_inbound_webhook_event");
    }

    private void recordFailure(PaymentWebhookEvent event, RuntimeException ex) {
        try {
            failureTx.executeWithoutResult(status -> {
                InboundWebhookEvent stored = eventRepository.findForUpdate(WebhookProvider.STRIPE, event.eventId())
                        .orElseGet(() -> eventRepository.save(new InboundWebhookEvent(WebhookProvider.STRIPE,
                                event.eventId(), event.type(), event.rawPayload(), clock.instant())));
                stored.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            });
        } catch (RuntimeException secondary) {
            log.error("No se pudo registrar el fallo del evento de Stripe {}", event.eventId(), secondary);
        }
    }
}
