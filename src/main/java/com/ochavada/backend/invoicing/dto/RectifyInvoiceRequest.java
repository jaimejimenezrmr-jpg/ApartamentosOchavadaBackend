package com.ochavada.backend.invoicing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Rectificación por diferencias. Sin {@code amount} se anula la factura completa; con {@code amount}
 * (IVA incluido, positivo) se rectifica parcialmente ese importe (p. ej. un reembolso parcial).
 */
public record RectifyInvoiceRequest(
        @NotBlank @Size(max = 500) String reason,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
