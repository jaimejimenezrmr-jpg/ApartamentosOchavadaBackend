package com.ochavada.backend.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Huella (hash) encadenada de los registros de facturación de alta, según la especificación técnica de
 * la AEAT para sistemas VERI*FACTU (RD 1007/2023, Orden HAC/1177/2024): SHA-256 en hexadecimal mayúsculas
 * de la cadena
 * <pre>
 * IDEmisorFactura=…&amp;NumSerieFactura=…&amp;FechaExpedicionFactura=dd-MM-yyyy&amp;TipoFactura=…
 * &amp;CuotaTotal=…&amp;ImporteTotal=…&amp;Huella=&lt;huella anterior&gt;&amp;FechaHoraHusoGenRegistro=yyyy-MM-ddTHH:mm:ss±hh:mm
 * </pre>
 * El primer registro de la cadena lleva {@code Huella=} vacío.
 */
@Component
public class VerifactuHasher {

    private static final DateTimeFormatter ISSUE_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);

    public String hash(String issuerTaxId, String invoiceNumber, LocalDate issueDate, InvoiceType type,
                       BigDecimal vatAmount, BigDecimal totalAmount, String previousHash, String recordTimestamp) {
        String input = "IDEmisorFactura=" + issuerTaxId.trim()
                + "&NumSerieFactura=" + invoiceNumber.trim()
                + "&FechaExpedicionFactura=" + ISSUE_DATE.format(issueDate)
                + "&TipoFactura=" + type.name()
                + "&CuotaTotal=" + amount(vatAmount)
                + "&ImporteTotal=" + amount(totalAmount)
                + "&Huella=" + (previousHash == null ? "" : previousHash.trim())
                + "&FechaHoraHusoGenRegistro=" + recordTimestamp.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
