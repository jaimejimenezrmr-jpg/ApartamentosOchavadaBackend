package com.ochavada.backend.reservation.backoffice.dto;

import com.ochavada.backend.guest.dto.GuestSummary;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentProvider;
import com.ochavada.backend.payment.PaymentStatus;
import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationChannel;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Ficha completa de la reserva para recepción. */
public record ReservationDetail(
        UUID id,
        String code,
        ReservationStatus status,
        ReservationChannel channel,
        String channelReference,
        Long beds24BookingId,
        /** Aviso de la última sincronización con el channel manager (revisión manual necesaria). */
        String channelSyncNote,
        BigDecimal commissionAmount,
        Ref roomType,
        Ref room,
        Ref ratePlan,
        GuestSummary holder,
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        LocalTime expectedArrivalTime,
        int adults,
        int children,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal balanceDue,
        String currency,
        PaymentMode paymentMode,
        String specialRequests,
        String internalNotes,
        Instant holdExpiresAt,
        Instant checkedInAt,
        Instant checkedOutAt,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt,
        long version,
        List<Night> nightCharges,
        List<PaymentLine> payments,
        List<InvoiceRef> invoices) {

    public record Ref(UUID id, String name) {
    }

    public record Night(LocalDate date, BigDecimal price) {
    }

    public record PaymentLine(UUID id, PaymentProvider provider, PaymentStatus status, BigDecimal amount,
                              BigDecimal refundedAmount, Instant paidAt, String reference, String cardLast4,
                              String failureMessage) {

        static PaymentLine from(Payment p) {
            return new PaymentLine(p.getId(), p.getProvider(), p.getStatus(), p.getAmount(), p.getRefundedAmount(),
                    p.getPaidAt(), p.getReference(), p.getCardLast4(), p.getFailureMessage());
        }
    }

    public record InvoiceRef(UUID id, String fullNumber, String invoiceType, String status, LocalDate issueDate,
                             BigDecimal totalAmount) {
    }

    public static ReservationDetail from(Reservation r, List<Payment> payments, List<InvoiceRef> invoices) {
        return new ReservationDetail(r.getId(), r.getCode(), r.getStatus(), r.getChannel(),
                r.getChannelBookingReference(), r.getBeds24BookingId(), r.getChannelSyncNote(), r.getCommissionAmount(),
                new Ref(r.getRoomType().getId(), r.getRoomType().getName()),
                r.getRoom() == null ? null : new Ref(r.getRoom().getId(), r.getRoom().getNumber()),
                r.getRatePlan() == null ? null : new Ref(r.getRatePlan().getId(), r.getRatePlan().getName()),
                GuestSummary.from(r.getHolder()), r.getCheckInDate(), r.getCheckOutDate(), r.nightCount(),
                r.getExpectedArrivalTime(), r.getAdults(), r.getChildren(), r.getTotalAmount(), r.getPaidAmount(),
                r.balanceDue(), r.getCurrency(), r.getPaymentMode(), r.getSpecialRequests(), r.getInternalNotes(),
                r.getHoldExpiresAt(), r.getCheckedInAt(), r.getCheckedOutAt(), r.getCancelledAt(),
                r.getCancellationReason(), r.getCreatedAt(), r.getVersion(),
                r.getNightCharges().stream().map(n -> new Night(n.getStayDate(), n.getPrice())).toList(),
                payments.stream().map(PaymentLine::from).toList(), invoices);
    }
}
