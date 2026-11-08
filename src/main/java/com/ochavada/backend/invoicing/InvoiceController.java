package com.ochavada.backend.invoicing;

import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.invoicing.dto.InvoiceResponse;
import com.ochavada.backend.invoicing.dto.IssueInvoiceRequest;
import com.ochavada.backend.invoicing.dto.RectifyInvoiceRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Facturación. Recepción emite y consulta; solo administración rectifica. */
@RestController
@RequestMapping("/api/v1/backoffice")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class InvoiceController {

    /** El documento lleva estilos en línea: se relaja la CSP de la API solo para esta respuesta. */
    private static final String DOCUMENT_CSP = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'";

    private final InvoiceService service;
    private final InvoiceDocumentService documentService;

    public InvoiceController(InvoiceService service, InvoiceDocumentService documentService) {
        this.service = service;
        this.documentService = documentService;
    }

    @PostMapping("/reservations/{reservationId}/invoices")
    public ResponseEntity<InvoiceResponse> issue(@PathVariable UUID reservationId,
                                                 @Valid @RequestBody IssueInvoiceRequest request) {
        InvoiceResponse invoice = service.issueForReservation(reservationId, request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/invoices/" + invoice.id())).body(invoice);
    }

    @GetMapping("/invoices")
    public PageResponse<InvoiceResponse> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(from, to, page, size);
    }

    @GetMapping("/invoices/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping(value = "/invoices/{id}/document", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> document(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", DOCUMENT_CSP)
                .body(documentService.html(id));
    }

    @PostMapping("/invoices/{id}/rectify")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InvoiceResponse> rectify(@PathVariable UUID id, @Valid @RequestBody RectifyInvoiceRequest request) {
        InvoiceResponse rectifying = service.rectify(id, request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/invoices/" + rectifying.id())).body(rectifying);
    }
}
