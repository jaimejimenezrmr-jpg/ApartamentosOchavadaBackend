package com.ochavada.backend.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.user.UserRole;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class InvoicingIntegrationTest extends AbstractApiTest {

    @Autowired
    private VerifactuHasher hasher;

    private UUID roomTypeId;
    private UUID guestId;

    @BeforeEach
    void seed() {
        String s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, base_price)
                values (?, ?, ?, 2, 2, 100.00) returning id
                """, UUID.class, "INV-" + s, "inv-" + s.toLowerCase(), "Estudio <b>Plaza</b> " + s);
        guestId = jdbc.queryForObject("insert into guest (first_name, last_name1) values ('Ana', 'López') returning id",
                UUID.class);
    }

    @Test
    void simplifiedInvoiceIsNumberedChainedAndImmutable() throws Exception {
        UUID first = reservation("330.00");
        UUID second = reservation("110.00");

        ResultActions a = issue(first, "{\"type\":\"F2\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("F2"))
                .andExpect(jsonPath("$.taxableBase").value(300.0))
                .andExpect(jsonPath("$.vatAmount").value(30.0))
                .andExpect(jsonPath("$.totalAmount").value(330.0))
                .andExpect(jsonPath("$.lines[0].quantity").value(3.0))
                .andExpect(jsonPath("$.lines[0].vatRate").value(10.0))
                .andExpect(jsonPath("$.issuer.taxId").value("B00000000"))
                .andExpect(jsonPath("$.verifactu.status").value("NOT_APPLICABLE"));
        ResultActions b = issue(second, "{\"type\":\"F2\"}").andExpect(status().isCreated());

        String numberA = read(a, "$.fullNumber");
        String numberB = read(b, "$.fullNumber");
        int year = today().getYear();
        assertThat(numberA).matches("T" + year + "-\\d{6}");
        assertThat(Integer.parseInt(numberB.substring(numberB.indexOf('-') + 1)))
                .isEqualTo(Integer.parseInt(numberA.substring(numberA.indexOf('-') + 1)) + 1);

        // Cadena de huellas: la segunda referencia a la primera y es reproducible.
        String hashA = read(a, "$.verifactu.hash");
        assertThat((String) read(b, "$.verifactu.previousHash")).isEqualTo(hashA);
        assertThat((String) read(a, "$.verifactu.hash")).isEqualTo(hasher.hash("B00000000", numberA,
                LocalDate.parse(read(a, "$.issueDate")), InvoiceType.F2, new java.math.BigDecimal("30.00"),
                new java.math.BigDecimal("330.00"), read(a, "$.verifactu.previousHash"),
                read(a, "$.verifactu.recordTimestamp")));

        // Una sola factura vigente por reserva.
        issue(first, "{\"type\":\"F2\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invoice.already_issued"));
        // La base de datos impide alterar los datos fiscales.
        assertThatThrownBy(() -> jdbc.update("update invoice set total_amount = 1 where full_number = ?", numberA))
                .rootCause().hasMessageContaining("inmutables");
    }

    @Test
    void completeInvoiceRequiresCustomerAndRendersEscapedDocument() throws Exception {
        UUID reservationId = reservation("220.00");
        issue(reservationId, "{\"type\":\"F1\",\"customerName\":\"Empresa\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("customerIdentified")));

        String id = read(issue(reservationId, """
                {"type":"F1","customerName":"Viajes <script>alert(1)</script> S.L.","customerTaxId":"b12345678",
                 "customerAddress":"Calle Mayor 1, Madrid","customerCountryCode":"ESP"}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullNumber").value(containsString("A" + today().getYear())))
                .andExpect(jsonPath("$.customer.taxId").value("B12345678")), "$.id");

        perform(get("/api/v1/backoffice/invoices/" + id + "/document"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'unsafe-inline'")))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Factura")))
                .andExpect(content().string(containsString("NIF: B00000000")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("Estudio &lt;b&gt;Plaza&lt;/b&gt;")));
    }

    @Test
    void rectificationByDifferencesIsAdminOnly() throws Exception {
        UUID reservationId = reservation("330.00");
        String id = read(issue(reservationId, "{\"type\":\"F2\"}").andExpect(status().isCreated()), "$.id");

        postJson("/api/v1/backoffice/invoices/" + id + "/rectify", "{\"reason\":\"Reembolso\"}", UserRole.RECEPTIONIST)
                .andExpect(status().isForbidden());

        // Rectificación parcial de 110 €.
        postJson("/api/v1/backoffice/invoices/" + id + "/rectify", "{\"reason\":\"Reembolso de una noche\",\"amount\":110.00}",
                UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("R5"))
                .andExpect(jsonPath("$.totalAmount").value(-110.0))
                .andExpect(jsonPath("$.taxableBase").value(-100.0))
                .andExpect(jsonPath("$.vatAmount").value(-10.0))
                .andExpect(jsonPath("$.rectifiedInvoiceNumber").isNotEmpty())
                .andExpect(jsonPath("$.fullNumber").value(containsString("R" + today().getYear())));
        // No se puede rectificar más de lo que queda.
        postJson("/api/v1/backoffice/invoices/" + id + "/rectify", "{\"reason\":\"Exceso\",\"amount\":300.00}", UserRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invoice.rectification_exceeds"));
        // El resto (220 €) anula la factura y permite emitir una nueva.
        postJson("/api/v1/backoffice/invoices/" + id + "/rectify", "{\"reason\":\"Datos del cliente erróneos\"}", UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(-220.0));
        perform(get("/api/v1/backoffice/invoices/" + id), UserRole.RECEPTIONIST)
                .andExpect(jsonPath("$.status").value("RECTIFIED"));
        issue(reservationId, "{\"type\":\"F2\"}").andExpect(status().isCreated());

        perform(get("/api/v1/backoffice/reservations/" + reservationId), UserRole.RECEPTIONIST)
                .andExpect(jsonPath("$.invoices.length()").value(4));
    }

    @Test
    void invoicesCanBeListedByIssueDate() throws Exception {
        String number = read(issue(reservation("110.00"), "{\"type\":\"F2\"}").andExpect(status().isCreated()), "$.fullNumber");
        perform(get("/api/v1/backoffice/invoices").param("from", today().toString()).param("to", today().toString())
                .param("size", "100"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullNumber", hasItem(number)));
    }

    // ------------------------------------------------------------------ helpers

    private UUID reservation(String total) {
        LocalDate in = today().minusDays(3);
        return jdbc.queryForObject("""
                insert into reservation (code, status, channel, room_type_id, holder_guest_id, check_in_date,
                                         check_out_date, adults, total_amount, payment_mode)
                values (?, 'CONFIRMED', 'PHONE', ?, ?, ?, ?, 2, ?, 'AT_PROPERTY') returning id
                """, UUID.class, "INV-" + suffix(), roomTypeId, guestId, in, today(), new java.math.BigDecimal(total));
    }

    private ResultActions issue(UUID reservationId, String json) throws Exception {
        return postJson("/api/v1/backoffice/reservations/" + reservationId + "/invoices", json, UserRole.RECEPTIONIST);
    }
}
