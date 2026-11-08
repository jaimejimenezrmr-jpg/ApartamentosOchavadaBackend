package com.ochavada.backend.reservation.backoffice;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentProvider;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.payment.gateway.PaymentGateway;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.dto.ActionRequests;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail;
import com.ochavada.backend.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Cobros en el alojamiento y reembolsos. */
@Slf4j
@Service
public class ReservationPaymentService {

    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final ReservationQueryService queryService;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReservationPaymentService(ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                                     PaymentGateway paymentGateway, ReservationQueryService queryService,
                                     AuditService auditService, PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.queryService = queryService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional
    public ReservationDetail registerPropertyPayment(UUID reservationId, ActionRequests.PropertyPayment req) {
        Reservation r = reservationRepository.findForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", reservationId));
        if (r.getStatus() == ReservationStatus.PENDING_PAYMENT) {
            throw new ConflictException("reservation.invalid_state",
                    "La reserva está pendiente de pago online; espere a que se confirme o caduque");
        }
        Payment payment = Payment.atProperty(r, req.provider(), req.amount(), r.getCurrency(),
                req.reference() == null || req.reference().isBlank() ? null : req.reference().trim(),
                CurrentUser.staffUserId().orElse(null), clock.instant());
        paymentRepository.save(payment);
        r.registerPayment(req.amount());
        reservationRepository.flush();
        auditService.record("payment.registered", "reservation", reservationId, Map.of(
                "provider", req.provider().name(), "amount", req.amount()));
        return queryService.toDetail(r);
    }

    /**
     * Reembolso total o parcial. Si el cobro fue con Stripe se ordena el reembolso a la pasarela (fuera de
     * transacción, con clave de idempotencia) y después se registra.
     */
    public ReservationDetail refund(UUID paymentId, ActionRequests.Refund req) {
        RefundTarget target = Objects.requireNonNull(tx.execute(s -> {
            Payment p = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("cobro", paymentId));
            if (req.amount().compareTo(p.refundableAmount()) > 0) {
                throw new BusinessRuleException("payment.refund_exceeds",
                        "El reembolso no puede superar " + p.refundableAmount() + " " + p.getCurrency());
            }
            return new RefundTarget(p.getReservation().getId(), p.getProvider(), p.getStripePaymentIntentId(),
                    p.getRefundedAmount());
        }));

        if (target.provider() == PaymentProvider.STRIPE) {
            // La clave depende de lo ya devuelto: un reintento de esta misma operación no duplica el reembolso.
            paymentGateway.refund(target.intentId(), req.amount(), req.reason(),
                    "refund-" + paymentId + "-" + target.alreadyRefunded().add(req.amount()).toPlainString());
        }
        return tx.execute(s -> {
            Payment p = paymentRepository.findForUpdate(paymentId).orElseThrow();
            Reservation r = reservationRepository.findForUpdate(target.reservationId()).orElseThrow();
            p.applyRefund(req.amount());
            r.registerRefund(req.amount());
            paymentRepository.flush();
            auditService.record("payment.refunded", "reservation", r.getId(), Map.of("paymentId", paymentId.toString(),
                    "provider", target.provider().name(), "amount", req.amount(), "reason", req.reason()));
            log.info("Reembolso de {} registrado para la reserva {}", req.amount(), r.getCode());
            return queryService.toDetail(r);
        });
    }

    private record RefundTarget(UUID reservationId, PaymentProvider provider, String intentId,
                                BigDecimal alreadyRefunded) {
    }
}
