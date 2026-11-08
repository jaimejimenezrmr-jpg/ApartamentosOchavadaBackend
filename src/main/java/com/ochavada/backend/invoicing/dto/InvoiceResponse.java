package com.ochavada.backend.invoicing.dto;

import com.ochavada.backend.invoicing.Invoice;
import com.ochavada.backend.invoicing.Invoice.InvoiceStatus;
import com.ochavada.backend.invoicing.Invoice.VerifactuStatus;
import com.ochavada.backend.invoicing.InvoiceLine;
import com.ochavada.backend.invoicing.InvoiceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        String fullNumber,
        InvoiceType type,
        InvoiceStatus status,
        LocalDate issueDate,
        LocalDate operationDate,
        UUID reservationId,
        String reservationCode,
        String rectifiedInvoiceNumber,
        String rectificationReason,
        Party issuer,
        Party customer,
        BigDecimal taxableBase,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String currency,
        String notes,
        List<Line> lines,
        Verifactu verifactu,
        Instant createdAt) {

    public record Party(String name, String taxId, String address, String countryCode) {
    }

    public record Line(int lineNumber, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal taxableBase, BigDecimal vatRate, BigDecimal vatAmount, BigDecimal lineTotal) {

        static Line from(InvoiceLine l) {
            return new Line(l.getLineNumber(), l.getDescription(), l.getQuantity(), l.getUnitPrice(), l.getTaxableBase(),
                    l.getVatRate(), l.getVatAmount(), l.getLineTotal());
        }
    }

    public record Verifactu(VerifactuStatus status, String hash, String previousHash, String recordTimestamp) {
    }

    public static InvoiceResponse from(Invoice i) {
        return new InvoiceResponse(i.getId(), i.getFullNumber(), i.getInvoiceType(), i.getStatus(), i.getIssueDate(),
                i.getOperationDate(),
                i.getReservation() == null ? null : i.getReservation().getId(),
                i.getReservation() == null ? null : i.getReservation().getCode(),
                i.getRectifiedInvoice() == null ? null : i.getRectifiedInvoice().getFullNumber(),
                i.getRectificationReason(),
                new Party(i.getIssuerName(), i.getIssuerTaxId(), i.getIssuerAddress(), "ESP"),
                new Party(i.getCustomerName(), i.getCustomerTaxId(), i.getCustomerAddress(), i.getCustomerCountryCode()),
                i.getTaxableBase(), i.getVatAmount(), i.getTotalAmount(), i.getCurrency(), i.getNotes(),
                i.getLines().stream().map(Line::from).toList(),
                new Verifactu(i.getVerifactuStatus(), i.getVerifactuHash(), i.getVerifactuPreviousHash(),
                        i.getVerifactuRecordTimestamp()),
                i.getCreatedAt());
    }
}
