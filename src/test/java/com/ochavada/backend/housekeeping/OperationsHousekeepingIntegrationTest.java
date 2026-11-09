package com.ochavada.backend.housekeeping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.user.UserRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fase 10: limpieza con lista de comprobación y fotos, inspección por otra persona, incidencias, objetos
 * olvidados y excepciones del panel.
 */
class OperationsHousekeepingIntegrationTest extends AbstractApiTest {

    private static final String HK = "/api/v1/backoffice/housekeeping";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private UUID roomTypeId;
    private UUID roomId;
    private String roomNumber;
    private UUID guestId;

    @BeforeEach
    void seed() {
        String s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price)
                values (?, ?, ?, 2, 2, 0, 80.00) returning id
                """, UUID.class, "OPS-" + s, "ops-" + s.toLowerCase(), "Estudio " + s);
        roomNumber = "O" + s.substring(0, 5);
        roomId = jdbc.queryForObject("insert into room (room_type_id, number, name) values (?, ?, 'La Torre') returning id",
                UUID.class, roomTypeId, roomNumber);
        guestId = jdbc.queryForObject("""
                insert into guest (first_name, last_name1, email, phone, document_type, document_number, nationality)
                values ('Marta', 'Ruiz', 'marta@example.com', '+34 600 000 001', 'NIF', '12345678Z', 'ESP') returning id
                """, UUID.class);
    }

    @Test
    void cleaningFollowsChecklistWithPhotosAndNeedsAnotherPersonToInspect() throws Exception {
        String template = read(postJson("/api/v1/admin/checklists", """
                {"name":"Salida estudio","roomTypeId":"%s","items":[
                  {"section":"Dormitorio","label":"Cama hecha","photoRequired":true},
                  {"section":"Baño","label":"Baño limpio","photoRequired":false}]}
                """.formatted(roomTypeId), UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].position").value(0))
                .andExpect(jsonPath("$.items[0].photoRequired").value(true)), "$.id");
        // Una sola lista activa por tipo de apartamento.
        postJson("/api/v1/admin/checklists", """
                {"name":"Otra","roomTypeId":"%s","items":[{"label":"X"}]}
                """.formatted(roomTypeId), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("checklist.duplicate_active"));
        perform(get("/api/v1/admin/checklists"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());

        String task = HK + "/tasks/" + createTask();
        // Sin empezar no hay lista; al empezar se copia la plantilla.
        perform(post(task + "/start"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksTotal").value(2))
                .andExpect(jsonPath("$.photosMissing").value(1))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("IN_PROGRESS"));

        // Editar la plantilla después no cambia la limpieza en curso.
        List<Number> versions = read(perform(get("/api/v1/admin/checklists"), UserRole.ADMIN),
                "$[?(@.id == '" + template + "')].version");
        long templateVersion = versions.getFirst().longValue();
        putJson("/api/v1/admin/checklists/" + template, """
                {"name":"Salida estudio v2","roomTypeId":"%s","items":[{"label":"Solo esto"}],"version":%d}
                """.formatted(roomTypeId, templateVersion), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        ResultActions detail = perform(get(task), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks", hasSize(2)))
                .andExpect(jsonPath("$.checks[0].label").value("Cama hecha"));
        String bed = read(detail, "$.checks[0].id");
        String bath = read(detail, "$.checks[1].id");

        // No se puede terminar con puntos pendientes.
        perform(post(task + "/complete"), UserRole.HOUSEKEEPING)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("housekeeping.checklist_incomplete"))
                .andExpect(jsonPath("$.missing", hasSize(2)));
        putJson(task + "/checks/" + bed, "{\"checked\":true}", UserRole.HOUSEKEEPING).andExpect(status().isOk());
        putJson(task + "/checks/" + bath, "{\"checked\":true,\"comment\":\"Falta gel\"}", UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Falta gel"));
        perform(post(task + "/complete"), UserRole.HOUSEKEEPING)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing[0]").value("Cama hecha (foto)"));

        // Las fotos se validan por su contenido, no por el nombre ni el tipo declarado.
        upload(task + "/checks/" + bed + "/photos", new MockMultipartFile("file", "cama.png", "image/png", "no soy una imagen".getBytes()), UserRole.HOUSEKEEPING)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("media.unsupported_type"));
        String photo = read(upload(task + "/checks/" + bed + "/photos", new MockMultipartFile("file", "cama.bin", "application/octet-stream", PNG), UserRole.HOUSEKEEPING)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png")), "$.id");
        perform(get("/api/v1/backoffice/media/" + photo), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        perform(post(task + "/complete"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedByUserId").value(userId(UserRole.HOUSEKEEPING).toString()))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("CLEAN"));

        // Quien limpió no puede inspeccionar su propia limpieza.
        perform(post(task + "/verify"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
        postJson(task + "/reject", "{\"reason\":\" \"}", UserRole.RECEPTIONIST).andExpect(status().isBadRequest());
        postJson(task + "/reject", "{\"reason\":\"Polvo bajo la cama\"}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rejectionCount").value(1))
                .andExpect(jsonPath("$.rejectionReason").value("Polvo bajo la cama"))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("DIRTY"));

        // Se repite: misma lista (la foto se conserva) pero hay que volver a marcarla.
        perform(post(task + "/start"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksTotal").value(2))
                .andExpect(jsonPath("$.checksDone").value(0))
                .andExpect(jsonPath("$.photosMissing").value(0));
        putJson(task + "/checks/" + bed, "{\"checked\":true}", UserRole.HOUSEKEEPING).andExpect(status().isOk());
        putJson(task + "/checks/" + bath, "{\"checked\":true}", UserRole.HOUSEKEEPING).andExpect(status().isOk());
        perform(post(task + "/complete"), UserRole.HOUSEKEEPING).andExpect(status().isOk());
        perform(post(task + "/verify"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("INSPECTED"));

        // Con la tarea cerrada ya no se tocan las fotos.
        perform(delete(task + "/checks/" + bed + "/photos/" + photo), UserRole.HOUSEKEEPING)
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select details->>'result' from audit_log where entity_id = ? and action = 'housekeeping.task_inspected'",
                String.class, task.substring(task.lastIndexOf('/') + 1))).containsExactlyInAnyOrder("REJECTED", "APPROVED");
    }

    @Test
    void onlyReceptionCanDeclareARoomReadyOrOutOfService() throws Exception {
        putJson(HK + "/rooms/" + roomId + "/status", "{\"status\":\"INSPECTED\"}", UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());
        putJson(HK + "/rooms/" + roomId + "/status", "{\"status\":\"OUT_OF_SERVICE\"}", UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());
        putJson(HK + "/rooms/" + roomId + "/status", "{\"status\":\"DIRTY\"}", UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.housekeepingStatus").value("DIRTY"));
        putJson(HK + "/rooms/" + roomId + "/status", "{\"status\":\"INSPECTED\"}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk());

        insertReservation("CHECKED_IN", today().minusDays(2), today());
        perform(get(HK + "/rooms"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomId == '" + roomId + "')].name").value("La Torre"))
                .andExpect(jsonPath("$[?(@.roomId == '" + roomId + "')].occupied").value(true))
                .andExpect(jsonPath("$[?(@.roomId == '" + roomId + "')].departureToday").value(true))
                .andExpect(jsonPath("$[?(@.roomId == '" + roomId + "')].housekeepingStatus").value("INSPECTED"));
    }

    @Test
    void housekeepingReportsIncidentsThatReceptionManages() throws Exception {
        String taskId = createTask();
        ResultActions created = postJson("/api/v1/backoffice/incidents", """
                {"taskId":"%s","title":"Lámpara de la mesilla no funciona","priority":"P3"}
                """.formatted(taskId), UserRole.HOUSEKEEPING)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("^INC-\\d{4}-\\d{4}$")))
                .andExpect(jsonPath("$.origin").value("HOUSEKEEPING"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.roomId").value(roomId.toString()))
                .andExpect(jsonPath("$.reportedByName").value("Usuario HOUSEKEEPING"));
        String id = read(created, "$.id");
        String incident = "/api/v1/backoffice/incidents/" + id;
        upload(incident + "/photos", new MockMultipartFile("file", "lampara.png", "image/png", PNG), UserRole.HOUSEKEEPING).andExpect(status().isCreated());

        // Limpieza ve la suya (y la ficha de la tarea la incluye), pero el centro de incidencias es de recepción.
        perform(get(incident), UserRole.HOUSEKEEPING).andExpect(status().isOk()).andExpect(jsonPath("$.photos", hasSize(1)));
        perform(get(HK + "/tasks/" + taskId), UserRole.HOUSEKEEPING)
                .andExpect(jsonPath("$.incidents[0].id").value(id));
        perform(get("/api/v1/backoffice/incidents"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
        postJson(incident + "/status", "{\"status\":\"RESOLVED\",\"version\":0}", UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());

        perform(get("/api/v1/backoffice/incidents?roomId=" + roomId), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        putJson(incident + "/priority", "{\"priority\":\"P2\",\"version\":0}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("P2"));
        postJson(incident + "/status", "{\"status\":\"RESOLVED\",\"version\":1}", UserRole.RECEPTIONIST)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("incident.resolution_required"));
        postJson(incident + "/status", "{\"status\":\"RESOLVED\",\"notes\":\"Bombilla cambiada\",\"version\":1}",
                UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("Bombilla cambiada"));
        postJson(incident + "/status", "{\"status\":\"CLOSED\",\"version\":2}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        postJson(incident + "/status", "{\"status\":\"IN_PROGRESS\",\"version\":3}", UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("incident.invalid_transition"));
        perform(get("/api/v1/backoffice/incidents?roomId=" + roomId), UserRole.RECEPTIONIST)
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void lostItemsAreLinkedToTheGuestWhoJustLeft() throws Exception {
        UUID stay = insertReservation("CHECKED_OUT", today().minusDays(3), today());
        String code = jdbc.queryForObject("select code from reservation where id = ?", String.class, stay);

        ResultActions created = postJson("/api/v1/backoffice/lost-items", """
                {"roomId":"%s","description":"Cargador de móvil blanco","storageLocation":"Recepción, cajón 2"}
                """.formatted(roomId), UserRole.HOUSEKEEPING)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("^OBJ-\\d{4}-\\d{4}$")))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.reservationCode").value(code))
                .andExpect(jsonPath("$.guestName").value("Marta Ruiz"))
                .andExpect(jsonPath("$.guestPhone").value("+34 600 000 001"));
        String item = "/api/v1/backoffice/lost-items/" + read(created, "$.id");

        perform(get("/api/v1/backoffice/lost-items"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
        postJson(item + "/status", "{\"status\":\"GUEST_NOTIFIED\",\"version\":0}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestNotifiedAt").exists());
        postJson(item + "/status", "{\"status\":\"SHIPPED\",\"version\":1}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").exists());
        postJson(item + "/status", "{\"status\":\"DISCARDED\",\"version\":2}", UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lost_item.closed"));
        upload(item + "/photos", new MockMultipartFile("file", "x.png", "image/png", PNG), UserRole.HOUSEKEEPING).andExpect(status().isConflict());
    }

    @Test
    void dashboardShowsTheDaysExceptionsAndCheckInNeedsAnInspectedRoom() throws Exception {
        long before = ((Number) read(perform(get("/api/v1/backoffice/dashboard"), UserRole.RECEPTIONIST),
                "$.operations.arrivalsNotReady")).longValue();
        jdbc.update("update room set housekeeping_status = 'CLEAN' where id = ?", roomId);
        UUID arrival = insertReservation("CONFIRMED", today(), today().plusDays(2));
        postJson("/api/v1/backoffice/incidents", """
                {"roomId":"%s","title":"Fuga en el baño","priority":"P1","origin":"GUEST"}
                """.formatted(roomId), UserRole.RECEPTIONIST)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("GUEST"));

        perform(get("/api/v1/backoffice/dashboard"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.arrivalsNotReady").value((int) before + 1))
                .andExpect(jsonPath("$.operations.openIncidentsP1").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // Limpia pero sin inspeccionar: no está LISTA para el huésped.
        perform(post("/api/v1/backoffice/reservations/" + arrival + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.room_not_ready"))
                .andExpect(jsonPath("$.housekeepingStatus").value("CLEAN"));
    }

    private ResultActions upload(String url, MockMultipartFile file, UserRole role) throws Exception {
        return mvc.perform(multipart(url).file(file).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(role)));
    }

    private String createTask() throws Exception {
        return read(postJson(HK + "/tasks", """
                {"roomId":"%s","taskType":"CHECKOUT_CLEAN","scheduledDate":"%s","priority":2}
                """.formatted(roomId, today()), UserRole.RECEPTIONIST).andExpect(status().isCreated()), "$.id");
    }

    private UUID insertReservation(String status, LocalDate checkIn, LocalDate checkOut) {
        Instant checkedIn = "CONFIRMED".equals(status) ? null : Instant.now().minusSeconds(86_400);
        Instant checkedOut = "CHECKED_OUT".equals(status) ? Instant.now().minusSeconds(3_600) : null;
        return jdbc.queryForObject("""
                insert into reservation (code, status, channel, room_type_id, room_id, holder_guest_id,
                                         check_in_date, check_out_date, adults, total_amount, payment_mode,
                                         checked_in_at, checked_out_at)
                values (?, ?, 'DIRECT', ?, ?, ?, ?, ?, 2, 160.00, 'AT_PROPERTY', ?, ?)
                returning id
                """, UUID.class, "OPS-" + suffix(), status, roomTypeId, roomId, guestId, checkIn, checkOut,
                checkedIn == null ? null : java.sql.Timestamp.from(checkedIn),
                checkedOut == null ? null : java.sql.Timestamp.from(checkedOut));
    }
}
