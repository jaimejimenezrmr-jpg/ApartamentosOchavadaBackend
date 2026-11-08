package com.ochavada.backend.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.support.AbstractApiTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Buscador público y consultas de inventario contra PostgreSQL real.
 */
class AvailabilityIntegrationTest extends AbstractApiTest {

    @Autowired
    private InventoryRepository inventory;

    private UUID roomTypeId;
    private String slug;
    private UUID roomA;
    private UUID roomB;
    private UUID guestId;
    private LocalDate checkIn;

    @BeforeEach
    void seed() {
        String s = suffix();
        slug = "busqueda-" + s.toLowerCase();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price)
                values (?, ?, ?, 4, 3, 2, 99.00) returning id
                """, UUID.class, "AV-" + s, slug, "Búsqueda " + s);
        roomA = jdbc.queryForObject("insert into room (room_type_id, number) values (?, ?) returning id",
                UUID.class, roomTypeId, "A" + s.substring(0, 6));
        roomB = jdbc.queryForObject("insert into room (room_type_id, number) values (?, ?) returning id",
                UUID.class, roomTypeId, "B" + s.substring(0, 6));
        jdbc.update("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay, is_default)
                values (?, 'FLEX', 'Flexible', true, 5, 25.00, 120.00, 2, true)
                """, roomTypeId);
        jdbc.update("""
                insert into rate_plan (room_type_id, code, name, refundable, prepayment_percent, base_price, min_stay)
                values (?, 'NR', 'No reembolsable', false, 100.00, 100.00, 1)
                """, roomTypeId);
        guestId = jdbc.queryForObject("insert into guest (first_name, last_name1) values ('Ana', 'Ruiz') returning id",
                UUID.class);
        checkIn = mondayAfter(60);
    }

    @Test
    void returnsRoomTypeWithUnitsAndQuotedOffers() throws Exception {
        ResultActions result = search(checkIn, checkIn.plusDays(3), 2, 1)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.nights").value(3))
                .andExpect(jsonPath("$.currency").value("EUR"));

        Map<String, Object> mine = resultFor(result);
        assertThat(mine.get("availableUnits")).isEqualTo(2);
        List<Map<String, Object>> offers = offers(mine);
        // Ordenadas: reservables primero y por precio total.
        assertThat(offers).extracting(o -> o.get("code")).containsExactly("NR", "FLEX");
        assertThat(offers.get(0).get("totalAmount")).isEqualTo(300.0);
        assertThat(offers.get(1).get("totalAmount")).isEqualTo(360.0);
        assertThat(offers.get(1).get("prepaymentAmount")).isEqualTo(90.0);
        assertThat(offers.get(1).get("freeCancellationUntil")).isEqualTo(checkIn.minusDays(5).toString());
        assertThat(offers.get(0).get("freeCancellationUntil")).isNull();
    }

    @Test
    void offersViolatingRestrictionsAreReturnedAsNotBookable() throws Exception {
        Map<String, Object> mine = resultFor(search(checkIn, checkIn.plusDays(1), 2, 0).andExpect(status().isOk()));

        List<Map<String, Object>> offers = offers(mine);
        assertThat(offers).extracting(o -> o.get("code")).containsExactly("NR", "FLEX");
        Map<String, Object> flex = offers.get(1);
        assertThat(flex.get("bookable")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restrictions = (List<Map<String, Object>>) flex.get("restrictions");
        assertThat(restrictions).extracting(r -> r.get("code")).containsExactly("MIN_STAY");
        assertThat(restrictions.getFirst().get("value")).isEqualTo(2);
    }

    @Test
    void reservationsBlocksAndUnassignedBookingsConsumeInventory() throws Exception {
        LocalDate checkOut = checkIn.plusDays(4);

        // Reserva en la unidad A solapando una noche → queda B.
        insertReservation(roomA, checkIn.plusDays(3), checkIn.plusDays(6), "CONFIRMED");
        assertThat(units(checkIn, checkOut)).isEqualTo(1);

        // Reserva cancelada: no ocupa.
        insertReservation(roomB, checkIn, checkOut, "CANCELLED");
        assertThat(units(checkIn, checkOut)).isEqualTo(1);

        // Reserva sin unidad asignada (p. ej. llegada de una OTA) → resta de la tipología.
        insertReservation(null, checkIn.plusDays(1), checkIn.plusDays(2), "CONFIRMED");
        assertThat(units(checkIn, checkOut)).isZero();
        // La tipología sin unidades no aparece en la búsqueda.
        assertThat(slugs(search(checkIn, checkOut, 2, 0))).doesNotContain(slug);

        // Las estancias encadenadas siguen siendo posibles: se libera el día de salida.
        assertThat(units(checkIn.plusDays(6), checkIn.plusDays(8))).isEqualTo(2);

        // Bloqueo de B: vuelve a no haber nada esas noches.
        jdbc.update("insert into room_block (room_id, start_date, end_date, block_type) values (?, ?, ?, 'MAINTENANCE')",
                roomB, checkIn.plusDays(6), checkIn.plusDays(7));
        assertThat(units(checkIn.plusDays(6), checkIn.plusDays(8))).isEqualTo(1);

        Map<LocalDate, Integer> nightly = inventory.nightlyAvailability(roomTypeId, checkIn, checkIn.plusDays(8));
        assertThat(nightly).hasSize(8);
        assertThat(nightly.get(checkIn)).isEqualTo(2);
        assertThat(nightly.get(checkIn.plusDays(1))).isEqualTo(1);      // reserva sin asignar
        assertThat(nightly.get(checkIn.plusDays(3))).isEqualTo(1);      // reserva en A
        assertThat(nightly.get(checkIn.plusDays(6))).isEqualTo(1);      // bloqueo en B
        assertThat(nightly.get(checkIn.plusDays(7))).isEqualTo(2);
    }

    @Test
    void occupancyAboveCapacityExcludesRoomType() throws Exception {
        assertThat(slugs(search(checkIn, checkIn.plusDays(2), 4, 0))).doesNotContain(slug);  // máx. 3 adultos
        assertThat(slugs(search(checkIn, checkIn.plusDays(2), 2, 3))).doesNotContain(slug);  // máx. 2 niños
        assertThat(slugs(search(checkIn, checkIn.plusDays(2), 3, 1))).contains(slug);
    }

    @Test
    void invalidSearchesReturnProblemDetails() throws Exception {
        search(checkIn, checkIn, 2, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("availability.invalid_dates"));
        search(today().minusDays(1), today().plusDays(2), 2, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("availability.past_date"));
        search(checkIn, checkIn.plusDays(31), 2, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("availability.stay_too_long"))
                .andExpect(jsonPath("$.maxNights").value(30));
        search(checkIn, checkIn.plusDays(2), 0, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.failed"));
        mvc.perform(get("/api/v1/public/availability").param("checkIn", "no-es-fecha").param("checkOut", "2027-01-01"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions search(LocalDate in, LocalDate out, int adults, int children) throws Exception {
        return mvc.perform(get("/api/v1/public/availability")
                .param("checkIn", in.toString())
                .param("checkOut", out.toString())
                .param("adults", String.valueOf(adults))
                .param("children", String.valueOf(children)));
    }

    private int units(LocalDate in, LocalDate out) {
        return inventory.availableUnitsForStay(List.of(roomTypeId), in, out).get(roomTypeId);
    }

    private Map<String, Object> resultFor(ResultActions result) throws Exception {
        List<Map<String, Object>> matches = read(result, "$.results[?(@.roomType.slug == '" + slug + "')]");
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> offers(Map<String, Object> roomTypeResult) {
        return (List<Map<String, Object>>) roomTypeResult.get("offers");
    }

    private static List<String> slugs(ResultActions result) throws Exception {
        return read(result.andExpect(status().isOk()), "$.results[*].roomType.slug");
    }

    private void insertReservation(UUID roomId, LocalDate in, LocalDate out, String status) {
        jdbc.update("""
                insert into reservation (code, status, channel, room_type_id, room_id, holder_guest_id,
                                         check_in_date, check_out_date, adults, total_amount, payment_mode,
                                         cancelled_at)
                values (?, ?, 'BOOKING_COM', ?, ?, ?, ?, ?, 2, 300.00, 'OTA_COLLECT', ?)
                """, "AV-" + suffix(), status, roomTypeId, roomId, guestId, in, out,
                "CANCELLED".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }
}
