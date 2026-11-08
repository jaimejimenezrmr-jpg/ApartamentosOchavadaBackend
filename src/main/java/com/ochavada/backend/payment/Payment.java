package com.ochavada.backend.payment;

import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.reservation.Reservation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Cobro asociado a una reserva. En Stripe corresponde a un PaymentIntent. */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends VersionedEntity {

    private static final int MAX_FAILURE_MESSAGE = 500;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = MAX_FAILURE_MESSAGE)
    private String failureMessage;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "registered_by_user_id")
    private UUID registeredByUserId;

    /** Referencia del cobro en el alojamiento (operación del datáfono, transferencia...). */
    @Column(name = "reference", length = 100)
    private String reference;

    /** Cobro registrado por recepción (efectivo, datáfono, transferencia). Nace completado. */
    public static Payment atProperty(Reservation reservation, PaymentProvider provider, BigDecimal amount,
                                     String currency, String reference, UUID userId, Instant paidAt) {
        if (provider == PaymentProvider.STRIPE || provider == PaymentProvider.OTA) {
            throw new IllegalArgumentException("Los cobros " + provider + " no se registran manualmente");
        }
        Payment payment = new Payment();
        payment.reservation = reservation;
        payment.provider = provider;
        payment.status = PaymentStatus.SUCCEEDED;
        payment.amount = amount;
        payment.refundedAmount = BigDecimal.ZERO;
        payment.currency = currency;
        payment.reference = reference;
        payment.registeredByUserId = userId;
        payment.paidAt = paidAt;
        return payment;
    }

    public BigDecimal refundableAmount() {
        return amount.subtract(refundedAmount);
    }

    /** Suma un reembolso (parcial o total) a lo ya devuelto. */
    public void applyRefund(BigDecimal refund) {
        if (status != PaymentStatus.SUCCEEDED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new ConflictException("payment.not_refundable", "Solo se pueden reembolsar cobros completados");
        }
        if (refund.signum() <= 0 || refund.compareTo(refundableAmount()) > 0) {
            throw new BusinessRuleException("payment.refund_exceeds",
                    "El reembolso debe ser positivo y no superar " + refundableAmount());
        }
        markRefunded(refundedAmount.add(refund));
    }

    public static Payment stripe(Reservation reservation, BigDecimal amount, String currency, String paymentIntentId) {
        Payment payment = new Payment();
        payment.reservation = reservation;
        payment.provider = PaymentProvider.STRIPE;
        payment.status = PaymentStatus.PENDING;
        payment.amount = amount;
        payment.refundedAmount = BigDecimal.ZERO;
        payment.currency = currency;
        payment.stripePaymentIntentId = paymentIntentId;
        return payment;
    }

    /** @return {@code true} si el cobro acaba de pasar a completado (no lo estaba ya). */
    public boolean markSucceeded(String chargeId, Instant paidAt) {
        if (status == PaymentStatus.SUCCEEDED || status == PaymentStatus.PARTIALLY_REFUNDED
                || status == PaymentStatus.REFUNDED) {
            return false;
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.stripeChargeId = chargeId;
        this.paidAt = paidAt;
        this.failureCode = null;
        this.failureMessage = null;
        return true;
    }

    public void markFailed(String code, String message) {
        if (status.isFinal()) {
            return;
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = truncate(code, 100);
        this.failureMessage = truncate(message, MAX_FAILURE_MESSAGE);
    }

    public void markRequiresAction() {
        if (!status.isFinal()) {
            this.status = PaymentStatus.REQUIRES_ACTION;
        }
    }

    public void markCancelled() {
        if (status != PaymentStatus.SUCCEEDED && !status.isFinal()) {
            this.status = PaymentStatus.CANCELLED;
        }
    }

    public void markRefunded(BigDecimal refunded) {
        this.refundedAmount = refunded.min(amount);
        this.status = refundedAmount.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
