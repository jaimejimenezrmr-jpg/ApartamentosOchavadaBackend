package com.ochavada.backend.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.channel.beds24.Beds24PollService;
import com.ochavada.backend.channel.beds24.Beds24PollService.PollSummary;
import com.ochavada.backend.channel.beds24.Beds24BookingImporter.ImportResult;
import com.ochavada.backend.integration.outbox.OutboxProcessor;
import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.support.FakeBeds24Server;
import com.ochavada.backend.user.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class Beds24IntegrationTest extends AbstractApiTest {

    private static final String WEBHOOK = "/api/v1/webhooks/beds24";
    private static final String TOKEN_HEADER = "X-Ochavada-Webhook-Token";
    private static final String WEBHOOK_TOKEN = "secreto-webhook-beds24";

    @Autowired
    private OutboxProcessor outbox;
    @Autowired
    private Beds24PollService pollService;

    private final FakeBeds24Server beds24 = FakeBeds24Server.get();

    private String s;
    private long beds24RoomId;
    private UUID roomTypeId;
    private UUID planId;
    private UUID room1;
    private UUID room2;

    @BeforeEach
    void seed() {
        s = suffix();
        beds24RoomId = ThreadLocalRandom.current().nextLong(100_000, 999_999_999);
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price, beds24_room_id)
                values (?, ?, ?, 4, 4, 2, 90.00, ?) returning id
                """, UUID.class, "B24-" + s, "b24-" + s.toLowerCase(), "Beds24 " + s, beds24RoomId);
        room1 = jdbc.queryForObject("insert into room (room_type_id, number, beds24_unit_id) values (?, ?, 1) returning id",
                UUID.class, roomTypeId, "X" + s.substring(0, 6));
        room2 = jdbc.queryForObject("insert into room (room_type_id, number, beds24_unit_id) values (?, ?, 2) returning id",
                UUID.class, roomTypeId, "Y" + s.substring(0, 6));
        planId = jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay, is_default, channel_rate_code)
                values (?, 'FLEX', 'Flexible', true, 3, 0.00, 110.00, 2, true, 'price1') returning id
                """, UUID.class, roomTypeId);
        jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, prepayment_percent, base_price, min_stay,
                                       is_default, channel_rate_code)
                values (?, 'NR', 'No reembolsable', false, 100.00, 95.00, 1, false, 'price2') returning id
                """, UUID.class, roomTypeId);
    }

    // ------------------------------------------------------------------ inventario (ARI)

    @Test
    void inventoryPricesAndRestrictionsArePushedPerDay() throws Exception {
        LocalDate stopSellDay = today().plusDays(12);
        putJson("/api/v1/admin/rate-plans/" + planId + "/daily-rates", """
                {"firstNight":"%s","lastNight":"%s","stopSell":true,"price":150.00}
                """.formatted(stopSellDay, stopSellDay), UserRole.ADMIN).andExpect(status().isOk());
        LocalDate ctaDay = today().plusDays(13);
        putJson("/api/v1/admin/rate-plans/" + planId + "/daily-rates", """
                {"firstNight":"%s","lastNight":"%s","closedToArrival":true,"minStay":3}
                """.formatted(ctaDay, ctaDay), UserRole.ADMIN).andExpect(status().isOk());
        deliver();

        Map<String, JsonNode> days = beds24.lastCalendarFor(beds24RoomId);
        JsonNode stopSell = days.get(stopSellDay.toString());
        assertThat(stopSell.path("to").asString()).isEqualTo(stopSellDay.toString());
        assertThat(stopSell.path("override").asString()).isEqualTo("blackout");
        assertThat(stopSell.path("price1").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(stopSell.path("price2").decimalValue()).isEqualByComparingTo("95.00");
        assertThat(stopSell.path("numAvail").asInt()).isEqualTo(2);
        JsonNode cta = days.get(ctaDay.toString());
        assertThat(cta.path("override").asString()).isEqualTo("noCheckIn");
        assertThat(cta.path("minStay").asInt()).isEqualTo(3);
        assertThat(cta.path("maxStay").asInt()).isZero();

        // Resincronización completa: todo el horizonte (60 días en tests), día a día.
        perform(post("/api/v1/admin/channel/beds24/sync"), UserRole.ADMIN).andExpect(status().isOk());
        deliver();
        days = beds24.lastCalendarFor(beds24RoomId);
        assertThat(days).containsKey(today().toString()).containsKey(today().plusDays(59).toString())
                .doesNotContainKey(today().plusDays(60).toString());
        assertThat(days.get(today().plusDays(30).toString()).path("price1").decimalValue()).isEqualByComparingTo("110.00");
        assertThat(days.get(today().plusDays(30).toString()).path("override").asString()).isEqualTo("none");
    }

    @Test
    void rateLimitedCalendarUpdatesAreRetriedByTheOutbox() throws Exception {
        LocalDate day = today().plusDays(20);
        beds24.failNextCalendarWith429(1);
        putJson("/api/v1/admin/rate-plans/" + planId + "/daily-rates", """
                {"firstNight":"%s","lastNight":"%s","price":175.00}
                """.formatted(day, day), UserRole.ADMIN).andExpect(status().isOk());
        deliver();
        assertThat(jdbc.queryForObject("""
                select last_error from outbox_event where aggregate_id = ? and event_type = 'inventory.sync'
                 order by created_at desc limit 1
                """, String.class, roomTypeId.toString())).contains("límite de créditos");

        retryFailed();
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(day.toString()).path("price1").decimalValue())
                .isEqualByComparingTo("175.00");
    }

    // ------------------------------------------------------------------ reservas de OTA

    @Test
    void otaBookingIsImportedModifiedAndCancelledThroughWebhooks() throws Exception {
        long bookingId = nextBookingId();
        LocalDate in = today().plusDays(15);
        beds24.putBooking(otaBooking(bookingId, "booking", in, in.plusDays(3), 2, "345.00", "51.75"));

        webhook("{\"booking\":{\"id\":" + bookingId + ",\"status\":\"confirmed\"}}", WEBHOOK_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received[0]").value(bookingId));
        deliver();

        Map<String, Object> r = reservationByBeds24Id(bookingId);
        assertThat(r.get("status")).isEqualTo("CONFIRMED");
        assertThat(r.get("channel")).isEqualTo("BOOKING_COM");
        assertThat(r.get("payment_mode")).isEqualTo("AT_PROPERTY");
        assertThat(r.get("room_id")).isEqualTo(room2);
        assertThat(r.get("channel_booking_reference")).isEqualTo("BDC-" + bookingId);
        assertThat(r.get("total_amount").toString()).isEqualTo("345.00");
        assertThat(r.get("commission_amount").toString()).isEqualTo("51.75");
        assertThat(jdbc.queryForObject("select count(*) from reservation_night where reservation_id = ?",
                Integer.class, r.get("id"))).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select g.country_code from guest g join reservation r on r.holder_guest_id = g.id where r.id = ?
                """, String.class, r.get("id"))).isEqualTo("FRA");
        assertThat(jdbc.queryForObject("select status from inbound_webhook_event where external_event_id like ?",
                String.class, "booking:" + bookingId + ":%")).isEqualTo("PROCESSED");
        // El inventario de esas noches baja en Beds24.
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.toString()).path("numAvail").asInt()).isEqualTo(1);

        // Un secreto incorrecto se rechaza sin encolar nada.
        webhook("{\"booking\":{\"id\":" + bookingId + "}}", "otro-secreto").andExpect(status().isUnauthorized());
        webhook("{\"booking\":{\"id\":" + bookingId + "}}", null).andExpect(status().isUnauthorized());

        // Modificación: una noche más y 3 adultos. El mismo cuerpo vuelve a importar el estado actual.
        beds24.updateBooking(bookingId, Map.of("departure", in.plusDays(4).toString(), "numAdult", 3, "price", new BigDecimal("460.00")));
        webhook("{\"booking\":{\"id\":" + bookingId + ",\"status\":\"confirmed\"}}", WEBHOOK_TOKEN).andExpect(status().isOk());
        deliver();
        r = reservationByBeds24Id(bookingId);
        assertThat(r.get("check_out_date").toString()).isEqualTo(in.plusDays(4).toString());
        assertThat(((Number) r.get("adults")).intValue()).isEqualTo(3);
        assertThat(r.get("total_amount").toString()).isEqualTo("460.00");
        assertThat(r.get("room_id")).isEqualTo(room2);
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.plusDays(3).toString()).path("numAvail").asInt()).isEqualTo(1);

        // Importar dos veces el mismo estado no cambia nada.
        assertThat(pollService.importOne(bookingId)).isEqualTo(ImportResult.UNCHANGED);

        // Cancelación en la OTA: la reserva se cancela y las noches vuelven a la venta.
        beds24.updateBooking(bookingId, Map.of("status", "cancelled"));
        webhook(null, WEBHOOK_TOKEN, bookingId).andExpect(status().isOk());
        deliver();
        r = reservationByBeds24Id(bookingId);
        assertThat(r.get("status")).isEqualTo("CANCELLED");
        assertThat(r.get("cancellation_reason")).isEqualTo("Cancelada en booking");
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.toString()).path("numAvail").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForList("select action from audit_log where entity_id = ?", String.class,
                r.get("id").toString()))
                .contains("reservation.imported_channel", "reservation.channel_modified", "reservation.cancelled_channel");
    }

    @Test
    void otaBookingsAreNeverRejectedEvenWithoutFreeUnits() throws Exception {
        LocalDate in = today().plusDays(25);
        long first = nextBookingId();
        long second = nextBookingId();
        long third = nextBookingId();
        beds24.putBooking(otaBooking(first, "airbnb", in, in.plusDays(2), 1, "200.00", "30.00"));
        beds24.putBooking(otaBooking(second, "expedia", in, in.plusDays(2), 1, "210.00", "35.00"));
        beds24.putBooking(otaBooking(third, "booking", in.plusDays(1), in.plusDays(3), 1, "220.00", "33.00"));
        for (long id : List.of(first, second, third)) {
            webhook("[{\"id\":" + id + "}]", WEBHOOK_TOKEN).andExpect(status().isOk());
        }
        deliver();

        Map<String, Object> airbnb = reservationByBeds24Id(first);
        assertThat(airbnb.get("channel")).isEqualTo("AIRBNB");
        assertThat(airbnb.get("payment_mode")).isEqualTo("OTA_COLLECT");
        assertThat(reservationByBeds24Id(second).get("channel")).isEqualTo("EXPEDIA");
        assertThat(List.of(airbnb.get("room_id"), reservationByBeds24Id(second).get("room_id")))
                .containsExactlyInAnyOrder(room1, room2);

        Map<String, Object> overbooked = reservationByBeds24Id(third);
        assertThat(overbooked.get("status")).isEqualTo("CONFIRMED");
        assertThat(overbooked.get("room_id")).isNull();
        assertThat((String) overbooked.get("channel_sync_note")).contains("posible sobreventa");
        perform(get("/api/v1/backoffice/reservations/" + overbooked.get("id")), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelSyncNote").value(overbooked.get("channel_sync_note")))
                .andExpect(jsonPath("$.commissionAmount").value(33.0));
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.plusDays(1).toString()).path("numAvail").asInt()).isZero();
    }

    @Test
    void unmappedRoomsAndInactiveStatusesAreHandledByThePoll() throws Exception {
        LocalDate in = today().plusDays(35);
        long pending = nextBookingId();
        long unmapped = nextBookingId();
        long valid = nextBookingId();
        Map<String, Object> request = otaBooking(pending, "booking", in, in.plusDays(2), 2, "180.00", "27.00");
        request.put("status", "request");
        beds24.putBooking(request);
        Map<String, Object> orphan = otaBooking(unmapped, "booking", in, in.plusDays(2), 2, "180.00", "27.00");
        orphan.put("roomId", beds24RoomId + 1_000_000_000L);
        beds24.putBooking(orphan);
        beds24.putBooking(otaBooking(valid, "google", in, in.plusDays(2), 2, "190.00", "0"));

        PollSummary summary = pollService.pollModified();
        assertThat(summary.deferred()).isGreaterThanOrEqualTo(1);
        assertThat(reservationByBeds24Id(valid).get("channel")).isEqualTo("GOOGLE");
        assertThat(jdbc.queryForObject("select count(*) from reservation where beds24_booking_id = ?", Integer.class,
                pending)).isZero();
        // La reserva sin vincular queda en el outbox para reintentarse cuando se vincule la habitación.
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_event where event_type = 'booking.import' and aggregate_id = ?
                """, Integer.class, String.valueOf(unmapped))).isEqualTo(1);
        // La marca del sondeo avanza para la siguiente pasada.
        assertThat(jdbc.queryForObject("select count(*) from integration_sync_state where name = 'beds24.bookings.modified'",
                Integer.class)).isEqualTo(1);
        // Limpia las reservas de prueba para no afectar a otras pasadas del sondeo.
        beds24.updateBooking(unmapped, Map.of("status", "inquiry"));
    }

    // ------------------------------------------------------------------ reservas propias

    @Test
    void ownReservationsArePushedAndTheirEchoIsIgnored() throws Exception {
        LocalDate in = today().plusDays(40);
        String json = """
                {"roomTypeId":"%s","ratePlanId":"%s","checkIn":"%s","checkOut":"%s","adults":2,"children":0,
                 "channel":"PHONE","guest":{"firstName":"Lucía","lastName1":"Gómez","lastName2":"Ruiz",
                 "email":"lucia-%s@example.com","phone":"+34 600 111 222","countryCode":"ESP"}}
                """.formatted(roomTypeId, planId, in, in.plusDays(3), s.toLowerCase());
        ResultActions created = postJson("/api/v1/backoffice/reservations", json, UserRole.RECEPTIONIST)
                .andExpect(status().isCreated());
        String id = read(created, "$.id");
        String code = read(created, "$.code");
        deliver();

        List<JsonNode> writes = beds24.bookingWritesFor(code, null);
        assertThat(writes).hasSize(1);
        JsonNode sent = writes.getFirst();
        assertThat(sent.path("roomId").asLong()).isEqualTo(beds24RoomId);
        assertThat(sent.path("unitId").asInt()).isEqualTo(1);
        assertThat(sent.path("arrival").asString()).isEqualTo(in.toString());
        assertThat(sent.path("lastName").asString()).isEqualTo("Gómez Ruiz");
        assertThat(sent.path("price").decimalValue()).isEqualByComparingTo("330.00");
        Long beds24Id = jdbc.queryForObject("select beds24_booking_id from reservation where id = ?::uuid", Long.class, id);
        assertThat(beds24Id).isNotNull();
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.toString()).path("numAvail").asInt()).isEqualTo(1);

        // Beds24 devuelve la reserva como webhook: es un eco y no se duplica.
        webhook("{\"id\":" + beds24Id + "}", WEBHOOK_TOKEN).andExpect(status().isOk());
        deliver();
        assertThat(jdbc.queryForObject("select count(*) from reservation where beds24_booking_id = ?", Integer.class,
                beds24Id)).isEqualTo(1);
        assertThat(pollService.importOne(beds24Id)).isEqualTo(ImportResult.OWN_ECHO);

        // La cancelación en el PMS se envía a Beds24 con su identificador.
        postJson("/api/v1/backoffice/reservations/" + id + "/cancel", "{\"reason\":\"Cliente anula\"}",
                UserRole.RECEPTIONIST).andExpect(status().isOk());
        deliver();
        List<JsonNode> cancellations = beds24.bookingWritesFor(null, beds24Id).stream()
                .filter(w -> "cancelled".equals(w.path("status").asString())).toList();
        assertThat(cancellations).hasSize(1);
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(in.toString()).path("numAvail").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ autenticación y administración

    @Test
    void expiredAccessTokensAreRenewedTransparently() throws Exception {
        beds24.expireAccessTokens();
        int grants = beds24.tokenGrants();
        LocalDate day = today().plusDays(45);
        putJson("/api/v1/admin/rate-plans/" + planId + "/daily-rates", """
                {"firstNight":"%s","lastNight":"%s","price":133.00}
                """.formatted(day, day), UserRole.ADMIN).andExpect(status().isOk());
        deliver();
        // El primer intento recibe 401 y descarta el token; el reintento obtiene uno nuevo.
        retryFailed();
        assertThat(beds24.tokenGrants()).isGreaterThan(grants);
        assertThat(beds24.lastCalendarFor(beds24RoomId).get(day.toString()).path("price1").decimalValue())
                .isEqualByComparingTo("133.00");
    }

    @Test
    void adminCanConnectInspectAndMapRooms() throws Exception {
        beds24.setPropertiesResponse("""
                {"success":true,"data":[{"id":321,"name":"Apartamentos Ochavada","roomTypes":[
                  {"id":%d,"name":"Dúplex","qty":2,"maxPeople":4,"units":[{"id":1,"name":"1"},{"id":2,"name":"2"}]},
                  {"id":11,"name":"Sin vincular","qty":1,"units":[{"id":1,"name":"1"}]}]}]}
                """.formatted(beds24RoomId));
        perform(get("/api/v1/admin/channel/beds24/rooms"), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mappedRoomTypeId").value(roomTypeId.toString()))
                .andExpect(jsonPath("$[0].units[1].mappedRoomNumber").value("Y" + s.substring(0, 6)))
                .andExpect(jsonPath("$[1].mappedRoomTypeId").value(nullValue()));

        postJson("/api/v1/admin/channel/beds24/connect", "{\"inviteCode\":\"caducado\"}", UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("beds24.invalid_invite_code"));
        postJson("/api/v1/admin/channel/beds24/connect", "{\"inviteCode\":\"" + FakeBeds24Server.INVITE_CODE + "\"}",
                UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));
        String stored = jdbc.queryForObject("select refresh_token_encrypted from integration_credential where provider = 'BEDS24'",
                String.class);
        assertThat(stored).isNotBlank().doesNotContain(FakeBeds24Server.INVITE_REFRESH_TOKEN);

        perform(get("/api/v1/admin/channel/beds24/status"), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.roomTypes[?(@.code == 'B24-" + s + "')].unitsMapped").value(2));
        perform(get("/api/v1/admin/channel/beds24/status"), UserRole.RECEPTIONIST).andExpect(status().isForbidden());
        deliver();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions webhook(String body, String token, Long... queryBookId) throws Exception {
        var request = post(WEBHOOK + (queryBookId.length > 0 ? "?bookid=" + queryBookId[0] : ""))
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return mvc.perform(request);
    }

    private Map<String, Object> otaBooking(long id, String channel, LocalDate in, LocalDate out, int adults,
                                           String price, String commission) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        b.put("propertyId", 321);
        b.put("roomId", beds24RoomId);
        b.put("unitId", 2);
        b.put("status", "confirmed");
        b.put("arrival", in.toString());
        b.put("departure", out.toString());
        b.put("numAdult", adults);
        b.put("numChild", 0);
        b.put("firstName", "Claire");
        b.put("lastName", "Dubois");
        b.put("email", "claire." + id + "@guest.booking.com");
        b.put("mobile", "+33 6 12 34 56 78");
        b.put("country", "fr");
        b.put("lang", "fr");
        b.put("price", new BigDecimal(price));
        b.put("commission", new BigDecimal(commission));
        b.put("channel", channel);
        b.put("apiSource", channel);
        b.put("apiReference", "BDC-" + id);
        b.put("comments", "Llegada tarde");
        return b;
    }

    private Map<String, Object> reservationByBeds24Id(long bookingId) {
        return jdbc.queryForMap("select * from reservation where beds24_booking_id = ?", bookingId);
    }

    private static long nextBookingId() {
        return ThreadLocalRandom.current().nextLong(10_000_000, 99_999_999);
    }

    /** Entrega el outbox hasta que no quede nada inmediato (los eventos generan otros eventos). */
    private void deliver() {
        for (int i = 0; i < 5 && outbox.processDue(500) > 0; i++) {
            // La importación de una reserva encola la sincronización de inventario.
        }
    }

    private void retryFailed() {
        jdbc.update("update outbox_event set next_attempt_at = now() - interval '1 second' where status = 'FAILED'"
                + " and destination = 'BEDS24' and event_type <> 'booking.import'");
        deliver();
    }
}
