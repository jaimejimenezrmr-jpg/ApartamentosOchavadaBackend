package com.ochavada.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.user.UserRole;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CatalogAndRatesIntegrationTest extends AbstractApiTest {

    // ------------------------------------------------------------------ catálogo

    @Test
    void adminManagesRoomTypeWithPhotosAndAmenities() throws Exception {
        String s = suffix();
        String roomTypeId = createRoomType("DUP-" + s, "Ático Dúplex " + s, 4);

        mvc.perform(get("/api/v1/admin/room-types/" + roomTypeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("atico-duplex-" + s.toLowerCase()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.version").value(0));

        // La primera foto pasa a ser portada automáticamente; marcar otra como portada desmarca la anterior.
        postJson("/api/v1/admin/room-types/" + roomTypeId + "/photos",
                photoJson("https://cdn.example.com/a.jpg", 1, false), UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos[0].cover").value(true));
        var withTwo = postJson("/api/v1/admin/room-types/" + roomTypeId + "/photos",
                photoJson("https://cdn.example.com/b.jpg", 0, true), UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos", hasSize(2)))
                .andExpect(jsonPath("$.photos[0].url").value("https://cdn.example.com/b.jpg"))
                .andExpect(jsonPath("$.photos[0].cover").value(true))
                .andExpect(jsonPath("$.photos[1].cover").value(false));
        String coverId = read(withTwo, "$.photos[0].id");

        perform(delete("/api/v1/admin/room-types/" + roomTypeId + "/photos/" + coverId), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos", hasSize(1)))
                .andExpect(jsonPath("$.photos[0].cover").value(true));

        String wifi = read(postJson("/api/v1/admin/amenities",
                "{\"code\":\"WIFI_%s\",\"name\":\"Wifi %s\",\"icon\":\"wifi\",\"category\":\"GENERAL\"}".formatted(s, s),
                UserRole.ADMIN).andExpect(status().isCreated()), "$.id");
        putJson("/api/v1/admin/room-types/" + roomTypeId + "/amenities",
                "{\"amenityIds\":[\"%s\"]}".formatted(wifi), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amenities[0].code").value("WIFI_" + s));

        // Un servicio en uso no puede eliminarse.
        perform(delete("/api/v1/admin/amenities/" + wifi), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("amenity.in_use"));

        // Ficha pública por slug.
        mvc.perform(get("/api/v1/public/room-types/atico-duplex-" + s.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, public"))
                .andExpect(jsonPath("$.name").value("Ático Dúplex " + s))
                .andExpect(jsonPath("$.photos", hasSize(1)))
                .andExpect(jsonPath("$.amenities[0].name").value("Wifi " + s));
        mvc.perform(get("/api/v1/public/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", hasItem("atico-duplex-" + s.toLowerCase())));
    }

    @Test
    void roomTypeValidationAndConcurrencyErrors() throws Exception {
        String s = suffix();
        // Adultos por encima de la ocupación máxima.
        postJson("/api/v1/admin/room-types", roomTypeJson("BAD-" + s, "Mal " + s, 2, 3, null), UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("occupancyConsistent")));

        String id = createRoomType("CON-" + s, "Concurrencia " + s, 2);
        postJson("/api/v1/admin/room-types", roomTypeJson("CON-" + s, "Otra " + s, 2, 2, null), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_type.duplicate_code"));

        putJson("/api/v1/admin/room-types/" + id, roomTypeJson("CON-" + s, "Concurrencia " + s, 3, 2, null),
                UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.version_required"));
        putJson("/api/v1/admin/room-types/" + id, roomTypeJson("CON-" + s, "Concurrencia " + s, 3, 2, 0L),
                UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.maxOccupancy").value(3));
        // Segunda edición con la versión ya superada: alguien la cambió entretanto.
        putJson("/api/v1/admin/room-types/" + id, roomTypeJson("CON-" + s, "Concurrencia " + s, 4, 2, 0L),
                UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data.concurrent_modification"))
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void roomsAndBlocksRespectRolesAndConflicts() throws Exception {
        String s = suffix();
        String roomTypeId = createRoomType("BLK-" + s, "Bloqueos " + s, 2);
        String roomId = createRoom(roomTypeId, s.substring(0, 6));

        // Recepción no puede dar de alta unidades, pero sí consultarlas y bloquearlas.
        postJson("/api/v1/admin/rooms", roomJson(roomTypeId, "X" + s.substring(0, 5)), UserRole.RECEPTIONIST)
                .andExpect(status().isForbidden());
        perform(get("/api/v1/backoffice/rooms").param("roomTypeId", roomTypeId), UserRole.HOUSEKEEPING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].housekeepingStatus").value("CLEAN"));

        LocalDate start = today().plusDays(20);
        String blockJson = "{\"roomId\":\"%s\",\"startDate\":\"%s\",\"endDate\":\"%s\",\"blockType\":\"MAINTENANCE\",\"reason\":\"Pintura\"}";
        postJson("/api/v1/backoffice/room-blocks", blockJson.formatted(roomId, start, start.plusDays(3)),
                UserRole.RECEPTIONIST)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByUserId").isNotEmpty());
        postJson("/api/v1/backoffice/room-blocks", blockJson.formatted(roomId, start.plusDays(2), start.plusDays(5)),
                UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_block.overlap"));
        postJson("/api/v1/backoffice/room-blocks", blockJson.formatted(roomId, start.plusDays(3), start.plusDays(4)),
                UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());

        // Un bloqueo no puede pisar una reserva viva.
        insertReservation(roomTypeId, roomId, start.plusDays(10), start.plusDays(12));
        postJson("/api/v1/backoffice/room-blocks", blockJson.formatted(roomId, start.plusDays(11), start.plusDays(13)),
                UserRole.RECEPTIONIST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_block.reservation_conflict"));

        perform(get("/api/v1/backoffice/room-blocks")
                .param("from", start.toString()).param("to", start.plusDays(30).toString())
                .param("roomId", roomId), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        // Sin filtro de unidad (parámetro nulo en la consulta).
        perform(get("/api/v1/backoffice/room-blocks")
                .param("from", start.toString()).param("to", start.plusDays(30).toString()), UserRole.RECEPTIONIST)
                .andExpect(status().isOk());

        // Una unidad con reservas no puede borrarse (integridad referencial → 409).
        perform(delete("/api/v1/admin/rooms/" + roomId), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data.reference"));
        // Ni una tipología con unidades.
        perform(delete("/api/v1/admin/room-types/" + roomTypeId), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_type.has_rooms"));
    }

    // ------------------------------------------------------------------ tarifas

    @Test
    void ratePlansSeasonsAndDailyRatesDriveTheCalendar() throws Exception {
        String s = suffix();
        String roomTypeId = createRoomType("RAT-" + s, "Tarifas " + s, 2);
        createRoom(roomTypeId, s.substring(0, 6));

        // El primer plan es predeterminado aunque no se pida.
        String flex = read(postJson("/api/v1/admin/rate-plans", ratePlanJson(roomTypeId, "FLEX", true, 7, "100.00", false),
                UserRole.ADMIN).andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultPlan").value(true)), "$.id");
        // Un no reembolsable descarta los días de cancelación aunque se envíen.
        String nr = read(postJson("/api/v1/admin/rate-plans", ratePlanJson(roomTypeId, "NR", false, 3, "90.00", true),
                UserRole.ADMIN).andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultPlan").value(true))
                .andExpect(jsonPath("$.freeCancellationDays").value(nullValue())), "$.id");
        perform(get("/api/v1/backoffice/rate-plans/" + flex), UserRole.RECEPTIONIST)
                .andExpect(jsonPath("$.defaultPlan").value(false));
        perform(get("/api/v1/backoffice/rate-plans").param("roomTypeId", roomTypeId), UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());

        LocalDate monday = mondayAfter(40);
        String seasonJson = "{\"name\":\"Alta\",\"firstNight\":\"%s\",\"lastNight\":\"%s\",\"nightlyPrice\":150.00,\"weekendPrice\":190.00,\"minStay\":2}";
        postJson("/api/v1/admin/rate-plans/" + flex + "/seasons", seasonJson.formatted(monday, monday.plusDays(13)),
                UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstNight").value(monday.toString()))
                .andExpect(jsonPath("$.lastNight").value(monday.plusDays(13).toString()));
        postJson("/api/v1/admin/rate-plans/" + flex + "/seasons", seasonJson.formatted(monday.plusDays(13), monday.plusDays(20)),
                UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("rate_season.overlap"));

        // Precio especial y CTA los miércoles de la primera semana; luego se elimina el precio del primero.
        putJson("/api/v1/admin/rate-plans/" + flex + "/daily-rates",
                "{\"firstNight\":\"%s\",\"lastNight\":\"%s\",\"daysOfWeek\":[\"WEDNESDAY\"],\"price\":210.00,\"closedToArrival\":true}"
                        .formatted(monday, monday.plusDays(13)), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedDays").value(2));
        putJson("/api/v1/admin/rate-plans/" + flex + "/daily-rates",
                "{\"firstNight\":\"%s\",\"lastNight\":\"%s\",\"clear\":[\"PRICE\"],\"closedToArrival\":false}"
                        .formatted(monday.plusDays(2), monday.plusDays(2)), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedOverrides").value(1));
        putJson("/api/v1/admin/rate-plans/" + flex + "/daily-rates",
                "{\"firstNight\":\"%s\",\"lastNight\":\"%s\",\"price\":1.00,\"clear\":[\"PRICE\"]}"
                        .formatted(monday, monday), UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("clearConsistent")));

        var calendar = perform(get("/api/v1/backoffice/rate-plans/" + flex + "/calendar")
                .param("from", monday.minusDays(1).toString())
                .param("to", monday.plusDays(9).toString()), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(11)));
        List<String> sources = read(calendar, "$.days[*].priceSource");
        assertThat(sources).containsExactly(
                "BASE",                                         // domingo previo, fuera de temporada
                "SEASON", "SEASON",                             // lunes, martes
                "SEASON",                                       // miércoles 1: ajuste eliminado
                "SEASON",                                       // jueves
                "SEASON_WEEKEND", "SEASON_WEEKEND",             // viernes, sábado
                "SEASON", "SEASON", "SEASON",                   // domingo, lunes, martes
                "DAILY_OVERRIDE");                              // miércoles 2: 210 €
        List<Boolean> cta = read(calendar, "$.days[*].closedToArrival");
        assertThat(cta.get(3)).isFalse();
        assertThat(cta.get(10)).isTrue();
        List<Integer> minStay = read(calendar, "$.days[*].minStay");
        assertThat(minStay.get(0)).isEqualTo(1);
        assertThat(minStay.get(1)).isEqualTo(2);
        List<Integer> units = read(calendar, "$.days[*].availableUnits");
        assertThat(units).containsOnly(1);

        // El plan predeterminado no puede dejar de serlo sin designar otro.
        putJson("/api/v1/admin/rate-plans/" + nr, ratePlanJson(roomTypeId, "NR", false, null, "90.00", false)
                        .replace("}", ",\"version\":1}"), UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("rate_plan.default_required"));
    }

    // ------------------------------------------------------------------ helpers

    private String createRoomType(String code, String name, int maxOccupancy) throws Exception {
        return read(postJson("/api/v1/admin/room-types", roomTypeJson(code, name, maxOccupancy, maxOccupancy, null),
                UserRole.ADMIN).andExpect(status().isCreated()), "$.id");
    }

    private String createRoom(String roomTypeId, String number) throws Exception {
        return read(postJson("/api/v1/admin/rooms", roomJson(roomTypeId, number), UserRole.ADMIN)
                .andExpect(status().isCreated()), "$.id");
    }

    private void insertReservation(String roomTypeId, String roomId, LocalDate checkIn, LocalDate checkOut) {
        String guestId = jdbc.queryForObject(
                "insert into guest (first_name, last_name1) values ('Test', 'Guest') returning id::text", String.class);
        jdbc.update("""
                insert into reservation (code, status, channel, room_type_id, room_id, holder_guest_id,
                                         check_in_date, check_out_date, adults, total_amount, payment_mode)
                values (?, 'CONFIRMED', 'DIRECT', ?::uuid, ?::uuid, ?::uuid, ?, ?, 2, 200.00, 'ONLINE')
                """, "T-" + suffix(), roomTypeId, roomId, guestId, checkIn, checkOut);
    }

    private static String roomTypeJson(String code, String name, int maxOccupancy, int maxAdults, Long version) {
        return """
                {"code":"%s","name":"%s","shortDescription":"Luminoso y céntrico","maxOccupancy":%d,
                 "maxAdults":%d,"maxChildren":0,"bedrooms":1,"bathrooms":1,"basePrice":95.00,
                 "displayOrder":1,"active":true%s}
                """.formatted(code, name, maxOccupancy, maxAdults, version == null ? "" : ",\"version\":" + version);
    }

    private static String roomJson(String roomTypeId, String number) {
        return "{\"roomTypeId\":\"%s\",\"number\":\"%s\",\"floor\":1,\"status\":\"ACTIVE\"}".formatted(roomTypeId, number);
    }

    private static String photoJson(String url, int order, boolean cover) {
        return "{\"url\":\"%s\",\"altText\":\"Salón\",\"displayOrder\":%d,\"cover\":%s}".formatted(url, order, cover);
    }

    private static String ratePlanJson(String roomTypeId, String code, boolean refundable, Integer cancelDays,
                                       String basePrice, boolean defaultPlan) {
        return """
                {"roomTypeId":"%s","code":"%s","name":"Tarifa %s","mealPlan":"ROOM_ONLY","refundable":%s,
                 %s"prepaymentPercent":30.00,"basePrice":%s,"minStay":1,"defaultPlan":%s,"active":true}
                """.formatted(roomTypeId, code, code, refundable,
                cancelDays == null ? "" : "\"freeCancellationDays\":" + cancelDays + ",", basePrice, defaultPlan);
    }
}
