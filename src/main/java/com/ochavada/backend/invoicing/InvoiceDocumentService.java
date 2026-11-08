package com.ochavada.backend.invoicing;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceDocumentService {

    private final InvoiceService invoiceService;
    private final InvoiceDocumentRenderer renderer;

    public InvoiceDocumentService(InvoiceService invoiceService, InvoiceDocumentRenderer renderer) {
        this.invoiceService = invoiceService;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public String html(UUID invoiceId) {
        return renderer.render(invoiceService.find(invoiceId));
    }
}
