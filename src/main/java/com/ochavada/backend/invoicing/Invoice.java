package com.ochavada.backend.invoicing;

import com.ochavada.backend.reservation.Reservation;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Factura emitida. Una vez guardada, sus datos fiscales son inmutables (trigger {@code trg_invoice_immutable}):
 * solo pueden cambiar el estado ({@code RECTIFIED}) y el seguimiento de la remisión VERI*FACTU.
 */
@Entity
@Table(name = "invoice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "series_id", nullable = false)
    private UUID seriesId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "full_number", nullable = false, length = 30)
    private String fullNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 2)
    private InvoiceType invoiceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rectified_invoice_id")
    private Invoice rectifiedInvoice;

    @Column(name = "rectification_reason", length = 500)
    private String rectificationReason;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "operation_date")
    private LocalDate operationDate;

    @Column(name = "issuer_name", nullable = false, length = 200)
    private String issuerName;

    @Column(name = "issuer_tax_id", nullable = false, length = 20)
    private String issuerTaxId;

    @Column(name = "issuer_address", nullable = false, length = 500)
    private String issuerAddress;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_tax_id", length = 30)
    private String customerTaxId;

    @Column(name = "customer_address", length = 500)
    private String customerAddress;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "customer_country_code", length = 3)
    private String customerCountryCode;

    @Column(name = "taxable_base", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxableBase;

    @Column(name = "vat_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "verifactu_status", nullable = false, length = 20)
    private VerifactuStatus verifactuStatus;

    @Column(name = "verifactu_hash", length = 64)
    private String verifactuHash;

    @Column(name = "verifactu_previous_hash", length = 64)
    private String verifactuPreviousHash;

    @Column(name = "verifactu_record_timestamp", length = 30)
    private String verifactuRecordTimestamp;

    @Column(name = "verifactu_csv", length = 100)
    private String verifactuCsv;

    @Column(name = "verifactu_sent_at")
    private Instant verifactuSentAt;

    @Column(name = "issued_by_user_id")
    private UUID issuedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("lineNumber ASC")
    private List<InvoiceLine> lines = new ArrayList<>();

    /** Construida exclusivamente por {@link InvoiceService}, que asigna número y huella. */
    Invoice(UUID seriesId, int number, String fullNumber, InvoiceType invoiceType, Reservation reservation,
            Invoice rectifiedInvoice, String rectificationReason, LocalDate issueDate, LocalDate operationDate,
            InvoicingProperties issuer, Customer customer, BigDecimal taxableBase, BigDecimal vatAmount,
            String currency, String notes, VerifactuStatus verifactuStatus, String hash, String previousHash,
            String recordTimestamp, UUID issuedByUserId, Instant createdAt) {
        this.seriesId = seriesId;
        this.number = number;
        this.fullNumber = fullNumber;
        this.invoiceType = invoiceType;
        this.status = InvoiceStatus.ISSUED;
        this.reservation = reservation;
        this.rectifiedInvoice = rectifiedInvoice;
        this.rectificationReason = rectificationReason;
        this.issueDate = issueDate;
        this.operationDate = operationDate;
        this.issuerName = issuer.issuerName();
        this.issuerTaxId = issuer.issuerTaxId();
        this.issuerAddress = issuer.issuerAddress();
        this.customerName = customer.name();
        this.customerTaxId = customer.taxId();
        this.customerAddress = customer.address();
        this.customerCountryCode = customer.countryCode();
        this.taxableBase = taxableBase;
        this.vatAmount = vatAmount;
        this.totalAmount = taxableBase.add(vatAmount);
        this.currency = currency;
        this.notes = notes;
        this.verifactuStatus = verifactuStatus;
        this.verifactuHash = hash;
        this.verifactuPreviousHash = previousHash;
        this.verifactuRecordTimestamp = recordTimestamp;
        this.issuedByUserId = issuedByUserId;
        this.createdAt = createdAt;
    }

    void addLine(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal base, BigDecimal vatRate,
                 BigDecimal vat) {
        lines.add(new InvoiceLine(this, lines.size() + 1, description, quantity, unitPrice, base, vatRate, vat));
    }

    void markRectified() {
        this.status = InvoiceStatus.RECTIFIED;
    }

    public record Customer(String name, String taxId, String address, String countryCode) {
    }

    public enum InvoiceStatus {
        ISSUED, RECTIFIED
    }

    public enum VerifactuStatus {
        NOT_APPLICABLE, PENDING, SENT, ACCEPTED, ACCEPTED_WITH_ERRORS, REJECTED
    }
}
