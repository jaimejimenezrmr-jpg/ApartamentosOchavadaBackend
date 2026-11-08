package com.ochavada.backend.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VerifactuHasherTest {

    private final VerifactuHasher hasher = new VerifactuHasher();

    /**
     * Ejemplo de registro de alta (primer registro de la cadena) del documento de la AEAT
     * "Detalle de las especificaciones técnicas para generación de la huella o hash de los registros".
     */
    @Test
    void matchesAeatReferenceExample() {
        String hash = hasher.hash("89890001K", "12345678/G33", LocalDate.of(2024, 1, 1), InvoiceType.F1,
                new BigDecimal("12.35"), new BigDecimal("123.45"), null, "2024-01-01T19:20:30+01:00");

        assertThat(hash).isEqualTo("3C464DAF61ACB827C65FDA19F352A4E3BDC2C640E9E9FC4CC058073F38F12F60");
    }

    @Test
    void chainsWithPreviousHash() {
        String first = hasher.hash("B00000000", "T2027-000001", LocalDate.of(2027, 3, 1), InvoiceType.F2,
                new BigDecimal("10.00"), new BigDecimal("110.00"), null, "2027-03-01T12:00:00+01:00");
        String second = hasher.hash("B00000000", "T2027-000002", LocalDate.of(2027, 3, 1), InvoiceType.F2,
                new BigDecimal("10.00"), new BigDecimal("110.00"), first, "2027-03-01T12:05:00+01:00");
        String secondWithoutChain = hasher.hash("B00000000", "T2027-000002", LocalDate.of(2027, 3, 1), InvoiceType.F2,
                new BigDecimal("10.00"), new BigDecimal("110.00"), null, "2027-03-01T12:05:00+01:00");

        assertThat(first).hasSize(64).matches("[0-9A-F]+");
        assertThat(second).isNotEqualTo(secondWithoutChain);
    }
}
