package com.ochavada.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ochavada.backend.security.jwt.JwtTokenService;
import com.ochavada.backend.support.AbstractIntegrationTest;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserRepository;
import com.ochavada.backend.user.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "Sup3r-Secret-Admin-Pass";
    private static final String COOKIE = "ochavada_rt";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ login

    @Test
    void bootstrapAdminCanLoginAndReceivesHttpOnlyRefreshCookie() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("  Admin@TEST.local ", ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").value(notNullValue()))
                .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andReturn().getResponse();

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=");
        assertThat(response.getContentAsString()).doesNotContain(response.getCookie(COOKIE).getValue());
    }

    @Test
    void wrongPasswordReturnsRfc7807Problem() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("nobody-" + UUID.randomUUID() + "@test.local", "whatever-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("auth.invalid_credentials"))
                .andExpect(jsonPath("$.type").value(endsWith("/problems/auth.invalid_credentials")))
                .andExpect(jsonPath("$.title").value("Credenciales no válidas"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void invalidLoginPayloadReturnsValidationProblem() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("validation.failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void malformedJsonReturnsNormalizedProblem() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("http.bad_request"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"));
    }

    @Test
    void accountIsLockedAfterRepeatedFailures() throws Exception {
        String email = "hk-" + UUID.randomUUID() + "@test.local";
        String password = "Housekeeping-Pass-123";
        createUser(email, password, UserRole.HOUSEKEEPING);

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(email, "bad-password")))
                    .andExpect(status().isUnauthorized());
        }

        // Con la cuenta bloqueada, incluso la contraseña correcta recibe la misma respuesta genérica.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid_credentials"));

        assertThat(userRepository.findByEmail(email).orElseThrow().getLockedUntil()).isAfter(Instant.now());
    }

    // ------------------------------------------------------------------ bearer token

    @Test
    void protectedEndpointWithoutTokenReturns401Problem() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("auth.unauthenticated"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/me"));
    }

    @Test
    void invalidTokenReturns401WithInvalidTokenChallenge() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("auth.unauthenticated"));
    }

    @Test
    void validStaffTokenAccessesCurrentUser() throws Exception {
        String accessToken = loginAsAdmin().accessToken();

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.lastLoginAt").value(notNullValue()));
    }

    @Test
    void guestTokenCannotAccessBackoffice() throws Exception {
        String guestToken = guestToken();

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("auth.forbidden"));

        mvc.perform(get("/api/v1/backoffice/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestTokenPassesGuestPortalAuthorization() throws Exception {
        // Ruta inexistente del portal: superar la autorización se traduce en un 404 normalizado.
        mvc.perform(get("/api/v1/guest/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("http.not_found"));
    }

    @Test
    void staffTokenCannotAccessGuestPortal() throws Exception {
        mvc.perform(get("/api/v1/guest/stay").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAsAdmin().accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    @Test
    void undeclaredRoutesAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/internal/secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/internal/secret").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginAsAdmin().accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicEndpointsIgnoreStaleBearerTokens() throws Exception {
        mvc.perform(get("/api/v1/public/room-types").header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void corsPreflightAllowsConfiguredOriginWithCredentials() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        mvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ refresh / logout

    @Test
    void refreshRotatesTokenAndDetectsReuse() throws Exception {
        String original = loginAsAdmin().refreshToken();

        String rotated = refresh(original).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(notNullValue()))
                .andReturn().getResponse().getCookie(COOKIE).getValue();
        assertThat(rotated).isNotEqualTo(original);

        // Reutilización inmediata (carrera entre pestañas): se rechaza pero no revoca la sesión.
        refresh(original).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));

        // Reutilización fuera del periodo de gracia: se considera robo y se revoca toda la familia.
        jdbc.update("update refresh_token set revoked_at = now() - interval '1 minute' where replaced_by_id is not null "
                + "and revocation_reason = 'ROTATED' and token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')", original);

        String latest = refresh(rotated).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(COOKIE).getValue();
        refresh(original).andExpect(status().isUnauthorized());
        refresh(latest).andExpect(status().isUnauthorized());

        Integer reuseRevoked = jdbc.queryForObject(
                "select count(*) from refresh_token where revocation_reason = 'REUSE_DETECTED' and family_id = "
                        + "(select family_id from refresh_token where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex'))",
                Integer.class, latest);
        assertThat(reuseRevoked).isPositive();
    }

    @Test
    void refreshWithoutCookieIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("auth.invalid_refresh_token"));

        refresh("token-inventado").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesSessionAndClearsCookie() throws Exception {
        String refreshToken = loginAsAdmin().refreshToken();

        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, refreshToken)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIsRejectedForDisabledUser() throws Exception {
        String email = "rcp-" + UUID.randomUUID() + "@test.local";
        String password = "Receptionist-Pass-123";
        createUser(email, password, UserRole.RECEPTIONIST);
        String refreshToken = login(email, password).refreshToken();

        jdbc.update("update app_user set enabled = false where email = ?", email);

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private record Tokens(String accessToken, String refreshToken) {
    }

    private Tokens loginAsAdmin() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private Tokens login(String email, String password) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String accessToken = JsonPath.read(response.getContentAsString(), "$.accessToken");
        return new Tokens(accessToken, response.getCookie(COOKIE).getValue());
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)));
    }

    private String guestToken() {
        return jwtTokenService.issueGuestAccessToken(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plus(Duration.ofHours(1))).value();
    }

    private void createUser(String email, String password, UserRole role) {
        userRepository.save(AppUser.create(email, passwordEncoder.encode(password), "Usuario de pruebas", role,
                Instant.now()));
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
