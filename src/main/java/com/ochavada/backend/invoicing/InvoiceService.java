package com.ochavada.backend.invoicing;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.invoicing.Invoice.Customer;
import com.ochavada.backend.invoicing.Invoice.InvoiceStatus;
import com.ochavada.backend.invoicing.Invoice.VerifactuStatus;
import com.ochavada.backend.invoicing.dto.InvoiceResponse;
import com.ochavada.backend.invoicing.dto.IssueInvoiceRequest;
import com.ochavada.backend.invoicing.dto.RectifyInvoiceRequest;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emisión de facturas conforme al RD 1619/2012:
 * <ul>
 *     <li>Numeración correlativa sin huecos por serie y ejercicio (fila de serie bloqueada con
 *     {@code FOR UPDATE} dentro de la misma transacción que la factura: si algo falla, el número no se consume).</li>
 *     <li>Los precios de alojamiento incluyen IVA: la base se obtiene dividiendo por (1 + tipo).</li>
 *     <li>Las facturas no se modifican ni se borran; se corrigen con rectificativas por diferencias.</li>
 *     <li>Cada registro lleva la huella VERI*FACTU encadenada con el anterior; un bloqueo consultivo
 *     serializa la generación para que la cadena no se bifurque.</li>
 * </ul>
 */
@Service
public class InvoiceService {

    /** Clave del bloqueo consultivo de PostgreSQL que serializa la cadena de huellas. */
    private static final long CHAIN_LOCK_KEY = 7_201_993_300_001L;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter RECORD_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceRepository repository;
    private final ReservationRepository reservationRepository;
    private final VerifactuHasher hasher;
    private final AuditService auditService;
    private final InvoicingProperties properties;
    private final PropertyProperties property;
    private final PropertyCalendar calendar;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InvoiceService(InvoiceRepository repository, ReservationRepository reservationRepository,
                          VerifactuHasher hasher, AuditService auditService, InvoicingProperties properties,
                          PropertyProperties property, PropertyCalendar calendar, JdbcClient jdbc, Clock clock) {
        this.repository = repository;
        this.reservationRepository = reservationRepository;
        this.hasher = hasher;
        this.auditService = auditService;
        this.properties = properties;
        this.property = property;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public InvoiceResponse issueForReservation(UUID reservationId, IssueInvoiceRequest req) {
        requireIssuer();
        Reservation r = reservationRepository.findForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("reserva", reservationId));
        if (r.getStatus() == ReservationStatus.PENDING_PAYMENT || r.getStatus() == ReservationStatus.CANCELLED) {
            throw new ConflictException("invoice.reservation_not_invoiceable",
                    "No se puede facturar una reserva en estado " + r.getStatus());
        }
        if (repository.existsActiveForReservation(reservationId)) {
            throw new ConflictException("invoice.already_issued",
                    "La reserva ya tiene una factura vigente; rectifíquela antes de emitir otra");
        }
        BigDecimal total = r.getTotalAmount();
        if (total.signum() <= 0) {
            throw new BusinessRuleException("invoice.zero_amount", "La reserva no tiene importe que facturar");
        }
        if (req.type() == InvoiceType.F2 && total.compareTo(properties.simplifiedMaxAmount()) > 0) {
            throw new BusinessRuleException("invoice.simplified_limit",
                    "El importe supera el máximo de una factura simplificada (" + properties.simplifiedMaxAmount()
                            + " €): emita una factura completa", Map.of("limit", properties.simplifiedMaxAmount()));
        }

        BigDecimal base = total.multiply(ONE_HUNDRED)
                .divide(ONE_HUNDRED.add(properties.vatRate()), 2, RoundingMode.HALF_UP);
        BigDecimal vat = total.subtract(base);
        int nights = (int) ChronoUnit.DAYS.between(r.getCheckInDate(), r.getCheckOutDate());
        LocalDate today = calendar.today();
        LocalDate operationDate = r.getCheckOutDate().equals(today) ? null : r.getCheckOutDate();
        String series = req.type() == InvoiceType.F1 ? properties.completeSeries() : properties.simplifiedSeries();
        Customer customer = new Customer(blankToNull(req.customerName()),
                req.customerTaxId() == null || req.customerTaxId().isBlank() ? null
                        : req.customerTaxId().trim().toUpperCase(Locale.ROOT),
                blankToNull(req.customerAddress()), req.customerCountryCode());

        Invoice invoice = build(series, req.type(), r, null, null, today, operationDate, customer, base, vat,
                r.getCurrency(), blankToNull(req.notes()));
        String description = "Alojamiento " + r.getRoomType().getName() + " · " + nights
                + (nights == 1 ? " noche" : " noches") + " (" + r.getCheckInDate() + " a " + r.getCheckOutDate()
                + ") · Reserva " + r.getCode();
        invoice.addLine(description, BigDecimal.valueOf(nights),
                base.divide(BigDecimal.valueOf(nights), 4, RoundingMode.HALF_UP), base, properties.vatRate(), vat);
        repository.saveAndFlush(invoice);
        auditService.record("invoice.issued", "invoice", invoice.getId(), Map.of(
                "number", invoice.getFullNumber(), "reservation", r.getCode(), "total", invoice.getTotalAmount()));
        return InvoiceResponse.from(invoice);
    }

    /** Rectificativa por diferencias (importes negativos): R4 para facturas completas, R5 para simplificadas. */
    @Transactional
    public InvoiceResponse rectify(UUID invoiceId, RectifyInvoiceRequest req) {
        requireIssuer();
        Invoice original = repository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("factura", invoiceId));
        if (original.getInvoiceType().isRectifying()) {
            throw new BadRequestException("invoice.cannot_rectify_rectifying",
                    "Una rectificativa no se rectifica; emita una nueva rectificativa de la factura original");
        }
        if (original.getStatus() == InvoiceStatus.RECTIFIED && req.amount() == null) {
            throw new ConflictException("invoice.already_rectified", "La factura ya fue rectificada");
        }
        BigDecimal alreadyRectified = repository.findByRectifiedInvoiceId(invoiceId).stream()
                .map(Invoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
        BigDecimal remaining = original.getTotalAmount().subtract(alreadyRectified);
        BigDecimal amount = req.amount() == null ? remaining : req.amount();
        if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new BusinessRuleException("invoice.rectification_exceeds",
                    "El importe a rectificar debe estar entre 0,01 y " + remaining);
        }

        BigDecimal base = amount.multiply(ONE_HUNDRED)
                .divide(ONE_HUNDRED.add(properties.vatRate()), 2, RoundingMode.HALF_UP).negate();
        BigDecimal vat = amount.negate().subtract(base);
        InvoiceType type = original.getInvoiceType() == InvoiceType.F1 ? InvoiceType.R4 : InvoiceType.R5;
        Customer customer = new Customer(original.getCustomerName(), original.getCustomerTaxId(),
                original.getCustomerAddress(), original.getCustomerCountryCode());

        Invoice rectifying = build(properties.rectifyingSeries(), type, original.getReservation(), original,
                req.reason().trim(), calendar.today(), original.getOperationDate(), customer, base, vat,
                original.getCurrency(), null);
        rectifying.addLine("Rectificación de la factura " + original.getFullNumber() + ": " + req.reason().trim(),
                BigDecimal.ONE, base.setScale(4, RoundingMode.HALF_UP), base, properties.vatRate(), vat);
        if (amount.compareTo(remaining) == 0) {
            original.markRectified();
        }
        repository.saveAndFlush(rectifying);
        auditService.record("invoice.rectified", "invoice", original.getId(), Map.of(
                "rectifying", rectifying.getFullNumber(), "amount", amount, "reason", req.reason().trim()));
        return InvoiceResponse.from(rectifying);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID id) {
        return InvoiceResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> list(LocalDate from, LocalDate to, int page, int size) {
        if (to.isBefore(from)) {
            throw new BadRequestException("validation.date_range", "La fecha 'to' no puede ser anterior a 'from'");
        }
        return PageResponse.of(repository.findByIssueDateBetween(from, to, PageRequest.of(Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"))),
                InvoiceResponse::from);
    }

    Invoice find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("factura", id));
    }

    // ------------------------------------------------------------------ numeración y huella

    private Invoice build(String seriesCode, InvoiceType type, Reservation reservation, Invoice rectified,
                          String reason, LocalDate issueDate, LocalDate operationDate, Customer customer,
                          BigDecimal base, BigDecimal vat, String currency, String notes) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK_KEY).query().singleRow();

        int year = issueDate.getYear();
        jdbc.sql("""
                        INSERT INTO invoice_series (code, fiscal_year, invoice_type, description)
                        VALUES (:code, :year, :type, :description)
                        ON CONFLICT (code, fiscal_year) DO NOTHING
                        """)
                .param("code", seriesCode).param("year", year).param("type", type.name())
                .param("description", "Serie " + seriesCode + " " + year)
                .update();
        Map<String, Object> series = jdbc.sql("""
                        SELECT id, next_number FROM invoice_series WHERE code = :code AND fiscal_year = :year FOR UPDATE
                        """)
                .param("code", seriesCode).param("year", year).query().singleRow();
        UUID seriesId = (UUID) series.get("id");
        int number = ((Number) series.get("next_number")).intValue();
        jdbc.sql("UPDATE invoice_series SET next_number = next_number + 1 WHERE id = :id").param("id", seriesId).update();

        String fullNumber = seriesCode + year + "-" + String.format(Locale.ROOT, "%06d", number);
        Instant now = clock.instant();
        String recordTimestamp = RECORD_TIMESTAMP.format(now.atZone(property.timeZone()));
        String previousHash = repository.findLastHash().orElse(null);
        String hash = hasher.hash(properties.issuerTaxId(), fullNumber, issueDate, type, vat, base.add(vat),
                previousHash, recordTimestamp);
        return new Invoice(seriesId, number, fullNumber, type, reservation, rectified, reason, issueDate,
                operationDate, properties, customer, base, vat, currency, notes,
                properties.verifactuEnabled() ? VerifactuStatus.PENDING : VerifactuStatus.NOT_APPLICABLE,
                hash, previousHash, recordTimestamp, CurrentUser.staffUserId().orElse(null), now);
    }

    private void requireIssuer() {
        if (!properties.isIssuerConfigured()) {
            throw new ConflictException("invoice.issuer_not_configured",
                    "Faltan los datos fiscales del emisor (INVOICE_ISSUER_NAME, INVOICE_ISSUER_TAX_ID, "
                            + "INVOICE_ISSUER_ADDRESS)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
