package com.ochavada.backend.invoicing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Facturación ({@code app.invoicing.*}).
 *
 * @param issuerName            razón social del emisor
 * @param issuerTaxId           NIF del emisor
 * @param issuerAddress         domicilio fiscal del emisor
 * @param vatRate               tipo de IVA del alojamiento (10 % en España); los precios lo incluyen
 * @param completeSeries        serie de facturas completas (F1)
 * @param simplifiedSeries      serie de facturas simplificadas (F2)
 * @param rectifyingSeries      serie de facturas rectificativas
 * @param simplifiedMaxAmount   importe máximo de una factura simplificada (art. 4 RD 1619/2012)
 * @param verifactuEnabled      si es {@code true} los registros quedan PENDING de remisión a la AEAT
 */
@Validated
@ConfigurationProperties("app.invoicing")
public record InvoicingProperties(
        String issuerName,
        String issuerTaxId,
        String issuerAddress,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @DefaultValue("10.00") BigDecimal vatRate,
        @Pattern(regexp = "^[A-Z0-9]{1,4}$") @DefaultValue("A") String completeSeries,
        @Pattern(regexp = "^[A-Z0-9]{1,4}$") @DefaultValue("T") String simplifiedSeries,
        @Pattern(regexp = "^[A-Z0-9]{1,4}$") @DefaultValue("R") String rectifyingSeries,
        @NotNull @DefaultValue("3000.00") BigDecimal simplifiedMaxAmount,
        @DefaultValue("false") boolean verifactuEnabled) {

    public boolean isIssuerConfigured() {
        return notBlank(issuerName) && notBlank(issuerTaxId) && notBlank(issuerAddress);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
