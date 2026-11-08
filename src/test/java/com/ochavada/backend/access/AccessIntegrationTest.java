package com.ochavada.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.auth.TokenHasher;
import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.support.FakeTtlockServer;
import com.ochavada.backend.user.UserRole;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AccessIntegrationTest extends AbstractApiTest {

    private final FakeTtlockServer ttlock = FakeTtlockServer.get();

    @Autowired
    private TokenHasher tokenHasher;

    private UUID roomTypeId;
    private UUID roomId;
    private UUID roomLockId;
    private long mainExternalId;
    private long roomExternalId;
    private String s;

    @BeforeEach
    void seed() {
        s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, base_price)
                values (?, ?, ?, 2, 2, 100.00) returning id
                """, UUID.class, "ACC-" + s, "acc-" + s.toLowerCase(), "Acceso " + s);
        roomId = jdbc.queryForObject("insert into room (room_type_id, number) values (?, ?) returning id",
                UUID.class, roomTypeId, "K" + s.substring(0, 6));
        mainExternalId = ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000);
        roomExternalId = mainExternalId + 1;
        // Un único portal activo para que la prueba sepa qué cerradura debe abrirse.
        jdbc.update("update lock_device set active = false where lock_type = 'MAIN_ENTRANCE'");
        jdbc.update("insert into lock_device (external_lock_id, name, lock_type) values (?, 'Portal', 'MAIN_ENTRANCE')",
                mainExternalId);
        roomLockId = jdbc.queryForObject("""
                insert into lock_device (external_lock_id, name, lock_type, room_id) values (?, ?, 'ROOM', ?) returning id
                """, UUID.class, roomExternalId, "Puerta " + s, roomId);
    }

    @Test
    void guestOpensBothDoorsAfterSelfCheckIn() throws Exception {
        Guest guest = guestWithReservation(today(), today().plusDays(2), "200.00");

        // Antes del check-in los botones están deshabilitados y la apertura se deniega (y se registra).
        guest.get("/api/v1/guest/doors").andExpect(status().isOk())
                .andExpect(jsonPath("$.doors[0].kind").value("MAIN_ENTRANCE"))
                .andExpect(jsonPath("$.doors[0].available").value(false))
                .andExpect(jsonPath("$.doors[0].reason").value("RESERVATION_NOT_CHECKED_IN"))
                .andExpect(jsonPath("$.selfCheckIn.available").value(false))
                .andExpect(jsonPath("$.selfCheckIn.blockers", hasItem("BALANCE_DUE")));
        guest.post("/api/v1/guest/doors/main-entrance/unlock")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.reservation_not_checked_in"));
        assertThat(ttlock.unlockRequestsFor(mainExternalId)).isEmpty();
        assertThat(logResults(guest.reservationId())).containsExactly("DENIED");

        // Autocheck-in bloqueado: saldo pendiente y unidad sin limpiar.
        jdbc.update("update room set housekeeping_status = 'DIRTY' where id = ?", roomId);
        guest.post("/api/v1/guest/check-in")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.self_check_in_unavailable"))
                .andExpect(jsonPath("$.blockers", hasItem("BALANCE_DUE")))
                .andExpect(jsonPath("$.blockers", hasItem("ROOM_NOT_READY")));
        jdbc.update("update room set housekeeping_status = 'INSPECTED' where id = ?", roomId);
        jdbc.update("update reservation set paid_amount = total_amount where id = ?", guest.reservationId());
        guest.post("/api/v1/guest/check-in")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers", hasItem("ONLINE_CHECKIN_PENDING")));
        jdbc.update("update reservation set online_checkin_completed_at = now() where id = ?", guest.reservationId());

        guest.post("/api/v1/guest/check-in").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.doors[0].available").value(true))
                .andExpect(jsonPath("$.doors[1].available").value(true))
                .andExpect(jsonPath("$.doors[1].name").value("Puerta " + s));
        guest.post("/api/v1/guest/check-in").andExpect(status().isOk());

        // «Abrir Entrada Principal» y «Abrir Mi Habitación»: llamadas servidor a servidor a TTLock.
        guest.post("/api/v1/guest/doors/main-entrance/unlock").andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.doorName").value("Portal"));
        guest.post("/api/v1/guest/doors/room/unlock").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ROOM"));

        List<Map<String, String>> calls = ttlock.unlockRequestsFor(mainExternalId);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).containsEntry("clientId", FakeTtlockServer.CLIENT_ID).containsKey("date");
        assertThat(ttlock.unlockRequestsFor(roomExternalId)).hasSize(1);

        assertThat(logResults(guest.reservationId())).containsExactly("DENIED", "SUCCESS", "SUCCESS");
        assertThat(jdbc.queryForObject("""
                select host(client_ip) from lock_access_log where reservation_id = ? and result = 'SUCCESS' limit 1
                """, String.class, guest.reservationId())).isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForList("select action from audit_log where entity_id = ?", String.class,
                guest.reservationId().toString())).contains("reservation.self_checked_in");
    }

    @Test
    void lockFailuresAreReportedAndAudited() throws Exception {
        Guest guest = checkedInGuest(today(), today().plusDays(1));
        ttlock.failUnlock(roomExternalId, -2012);

        guest.post("/api/v1/guest/doors/room/unlock")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("access.lock_provider_error"))
                .andExpect(jsonPath("$.providerErrorCode").value(-2012))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("gateway")));
        assertThat(jdbc.queryForObject("select provider_error_code from lock_access_log where reservation_id = ?",
                Integer.class, guest.reservationId())).isEqualTo(-2012);
    }

    @Test
    void expiredProviderTokenIsRenewedTransparentlyAndStoredEncrypted() throws Exception {
        Guest guest = checkedInGuest(today(), today().plusDays(1));
        guest.post("/api/v1/guest/doors/main-entrance/unlock").andExpect(status().isOk());
        int grantsBefore = ttlock.tokenGrants();

        ttlock.expireAllTokens();
        guest.post("/api/v1/guest/doors/main-entrance/unlock").andExpect(status().isOk());

        assertThat(ttlock.tokenGrants()).isEqualTo(grantsBefore + 1);
        String stored = jdbc.queryForObject("select access_token_encrypted from integration_credential where provider = 'TTLOCK'",
                String.class);
        assertThat(stored).isNotBlank().doesNotContain("access-");
    }

    @Test
    void roomWithoutLockAndStayWindowAreEnforced() throws Exception {
        jdbc.update("update lock_device set active = false where id = ?", roomLockId);
        Guest guest = checkedInGuest(today(), today().plusDays(1));
        guest.post("/api/v1/guest/doors/room/unlock")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.lock_not_assigned"));

        // Reserva marcada como alojada pero fuera de su ventana (llega dentro de 5 días).
        Guest future = checkedInGuest(today().plusDays(5), today().plusDays(7));
        future.post("/api/v1/guest/doors/main-entrance/unlock")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.outside_stay_window"));
        future.post("/api/v1/guest/doors/garaje/unlock").andExpect(status().isBadRequest());
    }

    @Test
    void unlocksArePerReservationRateLimited() throws Exception {
        Guest guest = checkedInGuest(today(), today().plusDays(1));
        for (int i = 0; i < 5; i++) {
            guest.post("/api/v1/guest/doors/main-entrance/unlock").andExpect(status().isOk());
        }
        guest.post("/api/v1/guest/doors/main-entrance/unlock")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limit.exceeded"));
        assertThat(jdbc.queryForObject("""
                select denial_reason from lock_access_log where reservation_id = ? and result = 'DENIED'
                """, String.class, guest.reservationId())).isEqualTo("RATE_LIMITED");
    }

    @Test
    void revokedLinkCannotOpenDoors() throws Exception {
        Guest guest = checkedInGuest(today(), today().plusDays(1));
        jdbc.update("update guest_access_token set revoked_at = now() where reservation_id = ?", guest.reservationId());
        guest.post("/api/v1/guest/doors/main-entrance/unlock")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("guest.invalid_access"));
    }

    @Test
    void staffCanOpenRemotelyAndReviewTheLog() throws Exception {
        perform(post("/api/v1/backoffice/locks/" + roomLockId + "/unlock"), UserRole.HOUSEKEEPING)
                .andExpect(status().isForbidden());
        perform(post("/api/v1/backoffice/locks/" + roomLockId + "/unlock"), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"));

        perform(get("/api/v1/backoffice/locks/" + roomLockId + "/access-log")
                .param("from", java.time.Instant.now().minusSeconds(3600).toString())
                .param("to", java.time.Instant.now().plusSeconds(60).toString()), UserRole.RECEPTIONIST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorType").value("STAFF"))
                .andExpect(jsonPath("$.content[0].userId").value(userId(UserRole.RECEPTIONIST).toString()));
    }

    @Test
    void adminRegistersLocksFromTheProviderAccount() throws Exception {
        long newLock = mainExternalId + 500;
        ttlock.addAccountLock(newLock, "Trastero " + s, 64);
        ttlock.addAccountLock(roomExternalId, "Puerta " + s, 15);

        perform(get("/api/v1/admin/locks/provider"), UserRole.ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.externalLockId == " + newLock + ")].registered").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.externalLockId == " + roomExternalId + ")].registered").value(hasItem(true)));

        postJson("/api/v1/admin/locks", lockJson(newLock, "ROOM", null), UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("roomConsistent")));
        postJson("/api/v1/admin/locks", lockJson(newLock, "ROOM", roomId), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lock.room_taken"));
        postJson("/api/v1/admin/locks", lockJson(roomExternalId, "COMMON_AREA", null), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("lock.duplicate"));
        postJson("/api/v1/admin/locks", lockJson(newLock, "COMMON_AREA", null), UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lockType").value("COMMON_AREA"));

        perform(post("/api/v1/admin/locks/sync"), UserRole.ADMIN).andExpect(status().isOk());
        perform(get("/api/v1/backoffice/locks"), UserRole.RECEPTIONIST).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + roomLockId + "')].batteryLevel").value(hasItem(15)))
                .andExpect(jsonPath("$[?(@.id == '" + roomLockId + "')].lowBattery").value(hasItem(true)));
        perform(get("/api/v1/admin/locks/provider"), UserRole.RECEPTIONIST).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    /** Reserva confirmada con enlace de acceso canjeado por un JWT de huésped. */
    private Guest guestWithReservation(LocalDate in, LocalDate out, String total) throws Exception {
        UUID guestId = jdbc.queryForObject("insert into guest (first_name, last_name1) values ('Lucía', 'Gil') returning id",
                UUID.class);
        UUID reservationId = jdbc.queryForObject("""
                insert into reservation (code, status, channel, room_type_id, room_id, holder_guest_id, check_in_date,
                                         check_out_date, adults, total_amount, payment_mode)
                values (?, 'CONFIRMED', 'PHONE', ?, ?, ?, ?, ?, 2, ?, 'AT_PROPERTY') returning id
                """, UUID.class, "ACC-" + suffix(), roomTypeId, roomId, guestId, in, out, new java.math.BigDecimal(total));
        String raw = tokenHasher.generateToken();
        jdbc.update("""
                insert into guest_access_token (reservation_id, token_hash, valid_from, expires_at)
                values (?, ?, now() - interval '1 minute', now() + interval '10 days')
                """, reservationId, tokenHasher.hash(raw));
        String jwt = read(mvc.perform(post("/api/v1/guest/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + raw + "\"}")).andExpect(status().isOk()), "$.accessToken");
        return new Guest(reservationId, jwt);
    }

    private Guest checkedInGuest(LocalDate in, LocalDate out) throws Exception {
        Guest guest = guestWithReservation(in, out, "0.00");
        jdbc.update("update reservation set status = 'CHECKED_IN', checked_in_at = now() where id = ?",
                guest.reservationId());
        return guest;
    }

    private List<String> logResults(UUID reservationId) {
        return jdbc.queryForList("select result from lock_access_log where reservation_id = ? order by id",
                String.class, reservationId);
    }

    private static String lockJson(long externalId, String type, UUID room) {
        return "{\"externalLockId\":%d,\"name\":\"Cerradura %d\",\"lockType\":\"%s\",%s\"active\":true}"
                .formatted(externalId, externalId, type, room == null ? "" : "\"roomId\":\"" + room + "\",");
    }

    private final class Guest {
        private final UUID reservationId;
        private final String jwt;

        Guest(UUID reservationId, String jwt) {
            this.reservationId = reservationId;
            this.jwt = jwt;
        }

        UUID reservationId() {
            return reservationId;
        }

        ResultActions get(String url) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
        }

        ResultActions post(String url) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
        }
    }
}
