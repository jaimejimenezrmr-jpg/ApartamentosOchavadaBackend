package com.ochavada.backend.invoicing.dto;

import com.ochavada.backend.invoicing.InvoiceType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Emisión de la factura de una reserva. La completa (F1) exige identificar al destinatario
 * (empresas y profesionales); la simplificada (F2) es la habitual para particulares.
 */
public record IssueInvoiceRequest(
        @NotNull InvoiceType type,
        @Size(max = 200) String customerName,
        @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Identificador fiscal no válido") String customerTaxId,
        @Size(max = 500) String customerAddress,
        @Pattern(regexp = "^[A-Z]{3}$", message = "País ISO 3166-1 alfa-3") String customerCountryCode,
        @Size(max = 1000) String notes) {

    @AssertTrue(message = "Solo pueden emitirse facturas completas (F1) o simplificadas (F2)")
    public boolean isOrdinaryType() {
        return type == null || type == InvoiceType.F1 || type == InvoiceType.F2;
    }

    @AssertTrue(message = "La factura completa exige nombre, NIF/identificador fiscal y domicilio del destinatario")
    public boolean isCustomerIdentified() {
        return type != InvoiceType.F1 || (notBlank(customerName) && notBlank(customerTaxId) && notBlank(customerAddress));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
