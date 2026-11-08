package com.ochavada.backend.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.support.FakePaymentGateway;
import com.ochavada.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PmsIntegrationTest extends AbstractApiTest {

    @Autowired
    private FakePaymentGateway gateway;

    private UUID roomTypeId;
    private UUID planId;
    private UUID roomA;
    private UUID roomB;
    private String roomANumber;
    private String s;

    @BeforeEach
    void seed() {
        s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price)
                values (?, ?, ?, 4, 4, 2, 90.00) returning id
                """, UUID.class, "PMS-" + s, "pms-" + s.toLowerCase(), "PMS " + s);
        roomANumber = "A" + s.substring(0, 6);
        roomA = jdbc.queryForObject("insert into room (room_type_id, number) values (?, ?) returning id", UUID.class,
                roomTypeId, roomANumber);
        roomB = jdbc.queryForObject("insert into room (room_type_id, number) values (?, ?) returning id", UUID.class,
                roomTypeId, "B" + s.substring(0, 6));
        planId = jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay, is_default)
                values (?, 'STD', 'Estándar', true, 3, 0.00, 100.00, 2, true) returning id
                """, UUID.class, roomTypeId);
    }

    // ------------------------------------------------------------------ alta y edición

    @Test
    void phoneReservationIsPricedAssignedAndAudited() throws Exception {
        LocalDate in = today().plusDays(10);
        ResultActions created = createManual(in, in.plusDays(3), roomA, null, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.channel").value("PHONE"))
                .andExpect(jsonPath("$.paymentMode").value("AT_PROPERTY"))
                .andExpect(jsonPath("$.room.name").value(roomANumber))
                .andExpect(jsonPath("$.totalAmount").value(300.0))
                .andExpect(jsonPath("$.nightCharges", hasSize(3)))
                .andExpect(jsonPath("$.holder.fullName").value("Pedro Martín Ruiz"));
        String id = read(created, "$.id");
        assertThat(jdbc.queryForObject("select created_by_user_id from reservation where id = ?::uuid", UUID.class, id))
                .isEqualTo(userId(UserRole.RECEPTIONIST));
        assertThat(jdbc.queryForList("select action from audit_log where entity_id = ?", String.class, id))
                .contains("reservation.created_manual");

        // La misma unidad no puede venderse dos veces.
        createManual(in.plusDays(1), in.plusDays(4), roomA, null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.room_unavailable"));
        // Sin unidad indicada se asigna la libre.
        createManual(in.plusDays(1), in.plusDays(4), null, null, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.room.id").value(roomB.toString()));
        // Ya no queda inventario.
        createManual(in.plusDays(1), in.plusDays(3), null, null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.sold_out"));
    }

    @Test
    void restrictionsCanOnlyBeSkippedExplicitlyAndPriceCanBeAgreed() throws Exception {
        LocalDate in = today().plusDays(20);
        createManual(in, in.plusDays(1), null, null, false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("reservation.rate_not_bookable"))
                .andExpect(jsonPath("$.restrictions[0].code").value("MIN_STAY"));
        ResultActions forced = createManual(in, in.plusDays(1), null, null, true).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select details->>'restrictionsOverridden' from audit_log where entity_id = ?",
                String.class, (String) read(forced, "$.id"))).contains("MIN_STAY");

        // Precio pactado de 250 € por 3 noches: el céntimo sobrante va a la última noche.
        createManual(in.plusDays(5), in.plusDays(8), null, new BigDecimal("250.00"), false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(250.0))
                .andExpect(jsonPath("$.nightCharges[0].price").value(83.33))
                .andExpect(jsonPath("$.nightCharges[2].price").value(83.34));
    }

    @Test
    void roomMovesAndStayChangesRespectOtherBookings() throws Exception {
        LocalDate in = today().plusDays(30);
        String first = read(createManual(in, in.plusDays(3), roomA, null, false).andExpect(status().isCreated()), "$.id");
        createManual(in.plusDays(3), in.plusDays(5), roomB, null, false).andExpect(status().isCreated());

        // Mover a B choca con la otra reserva de B en la noche 3? No: [in, in+3) y [in+3, in+5) son contiguas.
        putJson("/api/v1/backoffice/reservations/" + first + "/room", "{\"roomId\":\"" + roomB + "\"}",
                UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.id").value(roomB.toString()));

        // Ampliar la estancia en B hasta in+4 sí choca.
        long version = ((Number) read(perform(get("/api/v1/backoffice/reservations/" + first), UserRole.RECEPTIONIST),
                "$.version")).longValue();
        putJson("/api/v1/backoffice/reservations/" + first + "/stay", stayJson(in, in.plusDays(4), 2, version),
                UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.room_unavailable"));

        // Volver a A y ampliar: se recalcula el precio (4 noches × 100 €).
        putJson("/api/v1/backoffice/reservations/" + first + "/room", "{\"roomId\":\"" + roomA + "\"}",
                UserRole.RECEPTIONIST).andExpect(status().isOk());
        version = ((Number) read(perform(get("/api/v1/backoffice/reservations/" + first), UserRole.RECEPTIONIST),
                "$.version")).longValue();
        putJson("/api/v1/backoffice/reservations/" + first + "/stay", stayJson(in, in.plusDays(4), 3, version),
                UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(400.0))
                .andExpect(jsonPath("$.adults").value(3))
                .andExpect(jsonPath("$.nightCharges", hasSize(4)));
        // Versión antigua: conflicto de concurrencia.
        putJson("/api/v1/backoffice/reservations/" + first + "/stay", stayJson(in, in.plusDays(4), 2, version),
                UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data.concurrent_modification"));

        // Un bloqueo de mantenimiento tampoco puede pisarse.
        jdbc.update("insert into room_block (room_id, start_date, end_date, block_type) values (?, ?, ?, 'MAINTENANCE')",
                roomB, in.plusDays(10), in.plusDays(12));
        createManual(in.plusDays(9), in.plusDays(11), roomB, null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.room_blocked"));
    }

    // ------------------------------------------------------------------ recepción y gobernanza

    @Test
    void frontDeskCycleWithPaymentsAndHousekeeping() throws Exception {
        LocalDate in = today();
        String id = read(createManual(in, in.plusDays(2), roomA, null, false).andExpect(status().isCreated()), "$.id");
        String base = "/api/v1/backoffice/reservations/" + id;

        // La unidad está sucia: el check-in se bloquea salvo que se fuerce.
        jdbc.update("update room set housekeeping_status = 'DIRTY' where id = ?", roomA);
        perform(post(base + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.room_not_ready"));
        jdbc.update("update room set housekeeping_status = 'INSPECTED' where id = ?", roomA);
        // Sin los datos de los viajeros (parte SES) recepción no puede hacer el check-in.
        perform(post(base + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.travelers_incomplete"));
        putJson(base + "/travelers", twoAdultsJson(), UserRole.RECEPTIONIST).andExpect(status().isOk());
        perform(post(base + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.checkedInAt").value(notNullValue()));

        // Salida con saldo pendiente: bloqueada.
        perform(post(base + "/check-out"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.balance_due"))
                .andExpect(jsonPath("$.balanceDue").value(200.0));
        postJson(base + "/payments", "{\"provider\":\"STRIPE\",\"amount\":200.00}", UserRole.RECEPTIONIST)
                .andExpect(status().isBadRequest());
        postJson(base + "/payments", "{\"provider\":\"CARD_TERMINAL\",\"amount\":200.00,\"reference\":\"TPV-991\"}",
                UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(200.0))
                .andExpect(jsonPath("$.balanceDue").value(0.0))
                .andExpect(jsonPath("$.payments[0].reference").value("TPV-991"));
        perform(post(base + "/check-out"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));

        // La salida deja la unidad sucia con su tarea de limpieza.
        assertThat(jdbc.queryForObject("select housekeeping_status from room where id = ?", String.class, roomA))
                .isEqualTo("DIRTY");
        ResultActions board = perform(get("/api/v1/backoffice/housekeeping/tasks"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk());
        List<String> taskIds = read(board, "$[?(@.roomId == '" + roomA + "' && @.taskType == 'CHECKOUT_CLEAN')].id");
        assertThat(taskIds).hasSize(1);
        String task = "/api/v1/backoffice/housekeeping/tasks/" + taskIds.getFirst();

        perform(post(task + "/verify"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
        perform(post(task + "/start"), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignedUserId").value(userId(UserRole.HOUSEKEEPING).toString()))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("IN_PROGRESS"));
        postJson(task + "/complete", "{\"notes\":\"Falta reponer toallas\"}", UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("CLEAN"));
        perform(post(task + "/verify"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.roomHousekeepingStatus").value("INSPECTED"));
        perform(post(task + "/start"), UserRole.HOUSEKEEPING)
                .andExpect(status().isConflict());
    }

    @Test
    void checkInIsNotAllowedBeforeArrivalDay() throws Exception {
        LocalDate in = today().plusDays(3);
        String id = read(createManual(in, in.plusDays(2), roomA, null, false).andExpect(status().isCreated()), "$.id");
        perform(post("/api/v1/backoffice/reservations/" + id + "/check-in"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("reservation.too_early"));
        perform(post("/api/v1/backoffice/reservations/" + id + "/no-show"), UserRole.RECEPTIONIST)
                .andExpect(status().isConflict());
    }

    @Test
    void cancellationReleasesInventoryAndRefundsAreTracked() throws Exception {
        LocalDate in = today().plusDays(40);
        String id = read(createManual(in, in.plusDays(2), roomA, null, false).andExpect(status().isCreated()), "$.id");
        String paymentId = read(postJson("/api/v1/backoffice/reservations/" + id + "/payments",
                "{\"provider\":\"CASH\",\"amount\":80.00}", UserRole.RECEPTIONIST).andExpect(status().isOk()),
                "$.payments[0].id");

        postJson("/api/v1/backoffice/reservations/" + id + "/cancel", "{\"reason\":\"Enfermedad\"}", UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paidAmount").value(80.0));
        createManual(in, in.plusDays(2), roomA, null, false).andExpect(status().isCreated());

        // Reembolsos: solo administración y nunca por encima de lo cobrado.
        postJson("/api/v1/backoffice/payments/" + paymentId + "/refund", "{\"amount\":30.00,\"reason\":\"Gesto comercial\"}",
                UserRole.RECEPTIONIST).andExpect(status().isForbidden());
        postJson("/api/v1/backoffice/payments/" + paymentId + "/refund", "{\"amount\":90.00,\"reason\":\"Error\"}",
                UserRole.ADMIN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("payment.refund_exceeds"));
        postJson("/api/v1/backoffice/payments/" + paymentId + "/refund", "{\"amount\":30.00,\"reason\":\"Gesto comercial\"}",
                UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(50.0))
                .andExpect(jsonPath("$.payments[0].status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.payments[0].refundedAmount").value(30.0));
    }

    @Test
    void stripeRefundsGoThroughTheGateway() throws Exception {
        LocalDate in = today().plusDays(50);
        String id = read(createManual(in, in.plusDays(2), roomB, null, false).andExpect(status().isCreated()), "$.id");
        String intentId = "pi_test_refund_" + s;
        UUID paymentId = jdbc.queryForObject("""
                insert into payment (reservation_id, provider, status, amount, currency, stripe_payment_intent_id, paid_at)
                values (?::uuid, 'STRIPE', 'SUCCEEDED', 60.00, 'EUR', ?, now()) returning id
                """, UUID.class, id, intentId);
        jdbc.update("update reservation set paid_amount = 60.00 where id = ?::uuid", id);

        postJson("/api/v1/backoffice/payments/" + paymentId + "/refund", "{\"amount\":60.00,\"reason\":\"Cancelación gratuita\"}",
                UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(0.0));
        assertThat(gateway.refunds()).anySatisfy(r -> {
            assertThat(r.paymentIntentId()).isEqualTo(intentId);
            assertThat(r.amount()).isEqualByComparingTo("60.00");
        });
        assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
    }

    // ------------------------------------------------------------------ rack, panel y huéspedes

    @Test
    void rackAndDashboardReflectTheDay() throws Exception {
        LocalDate in = today();
        String code = read(createManual(in, in.plusDays(2), roomA, null, false).andExpect(status().isCreated()), "$.code");
        String guestId = jdbc.queryForObject("insert into guest (first_name, last_name1) values ('Sin', 'Unidad') returning id::text",
                String.class);
        jdbc.update("""
                insert into reservation (code, status, channel, room_type_id, holder_guest_id, check_in_date,
                                         check_out_date, adults, total_amount, payment_mode)
                values (?, 'CONFIRMED', 'BOOKING_COM', ?, ?::uuid, ?, ?, 2, 200.00, 'OTA_COLLECT')
                """, "OTA-" + s, roomTypeId, guestId, in, in.plusDays(1));
        jdbc.update("insert into room_block (room_id, start_date, end_date, block_type) values (?, ?, ?, 'OWNER_USE')",
                roomB, in.plusDays(3), in.plusDays(5));

        ResultActions rack = perform(get("/api/v1/backoffice/rack").param("from", in.toString())
                .param("to", in.plusDays(7).toString()), UserRole.RECEPTIONIST)
                .andExpect(status().isOk());
        List<String> bookingsInA = read(rack, "$.roomTypes[*].rooms[?(@.id == '" + roomA + "')].bookings[*].code");
        assertThat(bookingsInA).containsExactly(code);
        List<String> blocksInB = read(rack, "$.roomTypes[*].rooms[?(@.id == '" + roomB + "')].blocks[*].blockType");
        assertThat(blocksInB).containsExactly("OWNER_USE");
        List<String> unassigned = read(rack, "$.unassigned[*].code");
        assertThat(unassigned).contains("OTA-" + s);

        perform(get("/api/v1/backoffice/dashboard"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(in.toString()))
                .andExpect(jsonPath("$.arrivals[*].code", hasItem(code)))
                .andExpect(jsonPath("$.unassignedArrivals").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.occupancyPercent").value(notNullValue()))
                .andExpect(jsonPath("$.revenueMonthToDate").value(notNullValue()))
                .andExpect(jsonPath("$.currency").value("EUR"));
        perform(get("/api/v1/backoffice/dashboard"), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
    }

    @Test
    void guestProfileSearchAndHistory() throws Exception {
        String id = read(createManual(today().plusDays(60), today().plusDays(62), null, null, false)
                .andExpect(status().isCreated()), "$.holder.id");
        perform(get("/api/v1/backoffice/guests").param("q", "martín ruiz"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
        perform(get("/api/v1/backoffice/guests").param("q", "m"), UserRole.RECEPTIONIST)
                .andExpect(status().isBadRequest());

        ResultActions detail = perform(get("/api/v1/backoffice/guests/" + id), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stays", hasSize(1)))
                .andExpect(jsonPath("$.stays[0].holder").value(true))
                .andExpect(jsonPath("$.documentNumber").value(nullValue()));
        long version = ((Number) read(detail, "$.version")).longValue();

        putJson("/api/v1/backoffice/guests/" + id, guestJson("14002", version), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value("12345678Z"))
                .andExpect(jsonPath("$.municipalityCode").value("14002"));
        putJson("/api/v1/backoffice/guests/" + id, guestJson("290", version + 1), UserRole.RECEPTIONIST)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("municipalityCode")));
        perform(get("/api/v1/backoffice/guests/" + id), UserRole.HOUSEKEEPING).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions createManual(LocalDate in, LocalDate out, UUID roomId, BigDecimal totalOverride,
                                       boolean overrideRestrictions) throws Exception {
        String json = """
                {"roomTypeId":"%s","ratePlanId":"%s",%s"checkIn":"%s","checkOut":"%s","adults":2,"children":0,
                 "channel":"PHONE","guest":{"firstName":"Pedro","lastName1":"Martín","lastName2":"Ruiz",
                 "email":"pedro-%s@example.com","phone":"+34 611 222 333","countryCode":"ESP"},
                 %s"overrideRestrictions":%s,"internalNotes":"Cliente habitual"}
                """.formatted(roomTypeId, planId, roomId == null ? "" : "\"roomId\":\"" + roomId + "\",", in, out,
                s.toLowerCase(), totalOverride == null ? "" : "\"totalAmountOverride\":" + totalOverride + ",",
                overrideRestrictions);
        return postJson("/api/v1/backoffice/reservations", json, UserRole.RECEPTIONIST);
    }

    private static String twoAdultsJson() {
        String adult = """
                {"firstName":"%s","lastName1":"Martín","lastName2":"Ruiz","sex":"%s","birthDate":"%s","nationality":"%s",
                 "documentType":"%s","documentNumber":"%s",%s"email":"pedro@example.com","addressLine":"Calle Nueva 3",
                 "postalCode":"%s",%s"countryCode":"%s"}
                """;
        return "{\"travelers\":[" + adult.formatted("Pedro", "H", "1985-04-12", "ESP", "NIF", "12345678Z",
                "\"documentSupportNumber\":\"BAA000589\",", "14920", "\"municipalityCode\":\"14002\",", "ESP")
                + "," + adult.formatted("Claire", "M", "1987-09-30", "FRA", "PAS", "18AB12345", "", "69001",
                "\"city\":\"Lyon\",", "FRA") + "]}";
    }

    private static String stayJson(LocalDate in, LocalDate out, int adults, long version) {
        return "{\"checkIn\":\"%s\",\"checkOut\":\"%s\",\"adults\":%d,\"children\":0,\"version\":%d}"
                .formatted(in, out, adults, version);
    }

    private static String guestJson(String municipality, long version) {
        return """
                {"firstName":"Pedro","lastName1":"Martín","lastName2":"Ruiz","sex":"H","birthDate":"1985-04-12",
                 "nationality":"ESP","documentType":"NIF","documentNumber":"12345678z","documentSupportNumber":"BAA000589",
                 "email":"pedro@example.com","phone":"+34 611 222 333","addressLine":"Calle Nueva 3",
                 "postalCode":"14920","city":"Aguilar de la Frontera","municipalityCode":"%s","province":"Córdoba",
                 "countryCode":"ESP","version":%d}
                """.formatted(municipality, version);
    }

    @SuppressWarnings("unused")
    private static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }
}
