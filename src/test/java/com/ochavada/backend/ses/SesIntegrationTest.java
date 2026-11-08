package com.ochavada.backend.ses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.auth.TokenHasher;
import com.ochavada.backend.integration.outbox.OutboxProcessor;
import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.support.FakeSesServer;
import com.ochavada.backend.support.FakeSesServer.Submission;
import com.ochavada.backend.user.UserRole;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class SesIntegrationTest extends AbstractApiTest {

    private final FakeSesServer ses = FakeSesServer.get();

    @Autowired
    private OutboxProcessor outbox;
    @Autowired
    private SesBatchService batches;
    @Autowired
    private TokenHasher tokenHasher;

    private UUID roomTypeId;
    private UUID planId;
    private String s;

    @BeforeEach
    void seed() {
        s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price)
                values (?, ?, ?, 4, 4, 2, 80.00) returning id
                """, UUID.class, "SES-" + s, "ses-" + s.toLowerCase(), "SES " + s);
        for (int i = 0; i < 4; i++) {
            jdbc.update("insert into room (room_type_id, number) values (?, ?)", roomTypeId, i + s.substring(0, 6));
        }
        planId = jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay, is_default)
                values (?, 'STD', 'Estándar', true, 1, 0.00, 80.00, 1, true) returning id
                """, UUID.class, roomTypeId);
    }

    @Test
    void reservationAndTravelerEntryAreCommunicated() throws Exception {
        Created r = createReservation(today(), today().plusDays(2), 1, 1, "Lucía");
        settle();

        // RH: reserva de hospedaje con el titular (TI) y el establecimiento.
        Submission rh = submissionFor(r.code(), "A");
        assertThat(rh.type()).isEqualTo("RH");
        assertThat(rh.landlordCode()).isEqualTo("0000000001");
        assertThat(rh.xml()).contains("http://www.neg.hospedajes.mir.es/altaReservaHospedaje",
                "<establecimiento><codigo>0000000123</codigo></establecimiento>", "<rol>TI</rol>",
                "<referencia>" + r.code() + "</referencia>", "<tipoPago>DESTI</tipoPago>");
        assertThat(reportStatus(r.id(), "RESERVATION")).isEqualTo("ACCEPTED");

        // Registro de viajeros en recepción y check-in → parte de viajeros (PV).
        putJson("/api/v1/backoffice/reservations/" + r.id() + "/travelers", familyJson("Lucía"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.travelers.length()").value(2));
        perform(post("/api/v1/backoffice/reservations/" + r.id() + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        settle();

        Submission pv = ses.submissions().stream()
                .filter(x -> "PV".equals(x.type()) && x.xml().contains(r.code())).findFirst().orElseThrow();
        assertThat(pv.xml()).contains("http://www.neg.hospedajes.mir.es/altaParteHospedaje",
                "<codigoEstablecimiento>0000000123</codigoEstablecimiento>", "<numPersonas>2</numPersonas>",
                "<tipoDocumento>NIF</tipoDocumento>", "<numeroDocumento>12345678Z</numeroDocumento>",
                "<soporteDocumento>BAA000589</soporteDocumento>", "<codigoMunicipio>14002</codigoMunicipio>",
                "<pais>ESP</pais>", "<parentesco>HJ</parentesco>");
        assertThat(pv.xml()).doesNotContain("<rol>TI</rol>");
        assertThat(pv.xml().split("<rol>VI</rol>", -1)).hasSize(3);
        assertThat(pv.xml()).containsPattern("<fechaEntrada>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}</fechaEntrada>");

        perform(get("/api/v1/backoffice/reservations/" + r.id() + "/ses-reports"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ACCEPTED"))))
                .andExpect(jsonPath("$[?(@.type == 'TRAVELER_ENTRY')].communicationCode").value(hasItem(org.hamcrest.Matchers.notNullValue())));
    }

    @Test
    void checkInRequiresTravelersUnlessForcedAndDraftIsCompletedLater() throws Exception {
        Created r = createReservation(today(), today().plusDays(1), 1, 1, "Marta");

        perform(post("/api/v1/backoffice/reservations/" + r.id() + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.travelers_incomplete"))
                .andExpect(jsonPath("$.issues[*].field", hasItem("travelers")));
        postJson("/api/v1/backoffice/reservations/" + r.id() + "/check-in", "{\"force\":true}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        assertThat(reportStatus(r.id(), "TRAVELER_ENTRY")).isEqualTo("DRAFT");

        perform(get("/api/v1/backoffice/dashboard"), UserRole.RECEPTIONIST)
                .andExpect(jsonPath("$.sesReportsNeedingAction").value(greaterThanOrEqualTo(1)));

        putJson("/api/v1/backoffice/reservations/" + r.id() + "/travelers", familyJson("Marta"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        assertThat(reportStatus(r.id(), "TRAVELER_ENTRY")).isEqualTo("PENDING");
        settle();
        assertThat(reportStatus(r.id(), "TRAVELER_ENTRY")).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectedCommunicationCanBeCorrectedAndResubmitted() throws Exception {
        Created r = createReservation(today().plusDays(10), today().plusDays(12), 2, 0, "Rechazo");
        settle();
        assertThat(reportStatus(r.id(), "RESERVATION")).isEqualTo("REJECTED");
        String reportId = jdbc.queryForObject("""
                select id::text from traveler_report where reservation_id = ? and communication_type = 'RESERVATION'
                """, String.class, r.id());
        perform(get("/api/v1/backoffice/ses/reports/" + reportId), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.errorMessage").value(org.hamcrest.Matchers.containsString("nombre")))
                .andExpect(jsonPath("$.requestXml").value(org.hamcrest.Matchers.containsString("<nombre>Rechazo</nombre>")));

        // Recepción corrige el nombre del titular y reenvía la comunicación.
        jdbc.update("update guest set first_name = 'Rocío' where id = (select holder_guest_id from reservation where id = ?)",
                r.id());
        perform(post("/api/v1/backoffice/ses/reports/" + reportId + "/submit"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("PENDING"));
        settle();
        assertThat(reportStatus(r.id(), "RESERVATION")).isEqualTo("ACCEPTED");
        perform(post("/api/v1/backoffice/ses/reports/" + reportId + "/submit"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict());
    }

    @Test
    void cancellationAndStayChangesAnnulTheRegisteredReservation() throws Exception {
        Created r = createReservation(today().plusDays(20), today().plusDays(23), 2, 0, "Elena");
        settle();
        String code = jdbc.queryForObject("""
                select ses_communication_code from traveler_report where reservation_id = ? and status = 'ACCEPTED'
                """, String.class, r.id());

        // Cambio de fechas: anulación del alta registrada + nueva alta.
        long version = ((Number) read(perform(get("/api/v1/backoffice/reservations/" + r.id()), UserRole.RECEPTIONIST),
                "$.version")).longValue();
        putJson("/api/v1/backoffice/reservations/" + r.id() + "/stay",
                "{\"checkIn\":\"%s\",\"checkOut\":\"%s\",\"adults\":2,\"children\":0,\"version\":%d}"
                        .formatted(today().plusDays(21), today().plusDays(24), version), UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        settle();
        Submission annulment = submissionFor(code, "B");
        assertThat(annulment.xml()).contains("http://www.neg.hospedajes.mir.es/anularComunicacion",
                "<anul:codigoComunicacion>" + code + "</anul:codigoComunicacion>");
        // La anulación y la nueva alta se crean en la misma transacción: se comparan sin depender del orden.
        List<String> statuses = jdbc.queryForList(
                "select operation || ':' || status from traveler_report where reservation_id = ?", String.class, r.id());
        assertThat(statuses).containsExactlyInAnyOrder("A:CANCELLED", "B:ACCEPTED", "A:ACCEPTED");
        String newCode = jdbc.queryForObject("""
                select ses_communication_code from traveler_report
                 where reservation_id = ? and operation = 'A' and status = 'ACCEPTED'
                """, String.class, r.id());
        assertThat(newCode).isNotEqualTo(code);

        // Cancelación: se anula la nueva alta.
        postJson("/api/v1/backoffice/reservations/" + r.id() + "/cancel", "{\"reason\":\"Cambio de planes\"}",
                UserRole.RECEPTIONIST).andExpect(status().isOk());
        settle();
        assertThat(jdbc.queryForList(
                "select operation || ':' || status from traveler_report where reservation_id = ?", String.class, r.id()))
                .containsExactlyInAnyOrder("A:CANCELLED", "B:ACCEPTED", "A:CANCELLED", "B:ACCEPTED");
        assertThat(submissionFor(newCode, "B").xml()).contains(newCode);
    }

    @Test
    void guestCompletesOnlineCheckInFromThePortal() throws Exception {
        Created r = createReservation(today().plusDays(5), today().plusDays(7), 1, 1, "Sara");
        String jwt = guestToken(r.id());

        guest(MockMvcRequestBuilders.get("/api/v1/guest/travelers"), jwt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredTravelers").value(2))
                .andExpect(jsonPath("$.complete").value(false));

        String invalid = familyJson("Sara").replace("BAA000589", "").replace("\"relationship\":\"HJ\"", "\"relationship\":null");
        guest(MockMvcRequestBuilders.put("/api/v1/guest/travelers").contentType(MediaType.APPLICATION_JSON).content(invalid), jwt)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("checkin.invalid_travelers"))
                .andExpect(jsonPath("$.issues[*].field", hasItem("documentSupportNumber")))
                .andExpect(jsonPath("$.issues[*].field", hasItem("relationship")));

        guest(MockMvcRequestBuilders.put("/api/v1/guest/travelers").contentType(MediaType.APPLICATION_JSON)
                .content(familyJson("Sara")), jwt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.onlineCheckinCompletedAt").isNotEmpty());
        // Guardar de nuevo no duplica fichas de acompañantes.
        guest(MockMvcRequestBuilders.put("/api/v1/guest/travelers").contentType(MediaType.APPLICATION_JSON)
                .content(familyJson("Sara")), jwt).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from reservation_guest where reservation_id = ?",
                Integer.class, r.id())).isEqualTo(2);
        guest(MockMvcRequestBuilders.get("/api/v1/guest/stay"), jwt)
                .andExpect(jsonPath("$.onlineCheckinCompleted").value(true));
    }

    // ------------------------------------------------------------------ helpers

    private record Created(UUID id, String code) {
    }

    private Created createReservation(LocalDate in, LocalDate out, int adults, int children, String firstName)
            throws Exception {
        ResultActions result = postJson("/api/v1/backoffice/reservations", """
                {"roomTypeId":"%s","ratePlanId":"%s","checkIn":"%s","checkOut":"%s","adults":%d,"children":%d,
                 "channel":"PHONE","guest":{"firstName":"%s","lastName1":"García","lastName2":"López",
                 "email":"titular-%s@example.com","phone":"+34 600 111 222","countryCode":"ESP"}}
                """.formatted(roomTypeId, planId, in, out, adults, children, firstName, UUID.randomUUID()),
                UserRole.RECEPTIONIST).andExpect(status().isCreated());
        return new Created(UUID.fromString(read(result, "$.id")), read(result, "$.code"));
    }

    /** Titular adulto con DNI + menor (hijo). */
    private static String familyJson(String holderName) {
        return """
                {"travelers":[
                  {"firstName":"%s","lastName1":"García","lastName2":"López","sex":"M","birthDate":"1988-05-04",
                   "nationality":"ESP","documentType":"NIF","documentNumber":"12345678z","documentSupportNumber":"BAA000589",
                   "email":"lucia@example.com","phone":"+34 600 111 222","addressLine":"Calle Mayor 1",
                   "postalCode":"14920","city":"Aguilar de la Frontera","municipalityCode":"14002","province":"Córdoba","countryCode":"ESP"},
                  {"firstName":"Pablo","lastName1":"García","lastName2":"López","sex":"H","birthDate":"2018-02-02",
                   "nationality":"ESP","email":"lucia@example.com","addressLine":"Calle Mayor 1","postalCode":"14920",
                   "city":"Aguilar de la Frontera","municipalityCode":"14002","countryCode":"ESP","relationship":"HJ"}]}
                """.formatted(holderName);
    }

    /** Envía lo pendiente del outbox y consulta lotes hasta que no queda nada por resolver. */
    private void settle() {
        for (int round = 0; round < 6; round++) {
            outbox.processDue(500);
            for (int i = 0; i < 30 && batches.checkSubmitted() > 0; i++) {
                // consulta lotes de 10 en 10
            }
            // Las anulaciones que esperaban el código del alta se reintentan en el outbox.
            jdbc.update("update outbox_event set next_attempt_at = now() - interval '1 second' where status = 'FAILED'"
                    + " and destination = 'SES_HOSPEDAJES'");
        }
    }

    private Submission submissionFor(String text, String operation) {
        return ses.submissionsContaining(text).stream().filter(x -> operation.equals(x.operation()))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("Sin comunicación " + operation + " con " + text));
    }

    private String reportStatus(UUID reservationId, String type) {
        return jdbc.queryForObject("""
                select status from traveler_report where reservation_id = ? and communication_type = ? and operation = 'A'
                 order by created_at desc limit 1
                """, String.class, reservationId, type);
    }

    private String guestToken(UUID reservationId) throws Exception {
        String raw = tokenHasher.generateToken();
        jdbc.update("""
                insert into guest_access_token (reservation_id, token_hash, valid_from, expires_at)
                values (?, ?, now() - interval '1 minute', now() + interval '10 days')
                """, reservationId, tokenHasher.hash(raw));
        return read(mvc.perform(post("/api/v1/guest/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + raw + "\"}")).andExpect(status().isOk()), "$.accessToken");
    }

    private ResultActions guest(MockHttpServletRequestBuilder request, String jwt) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
    }
}
