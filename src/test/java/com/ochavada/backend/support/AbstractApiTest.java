package com.ochavada.backend.support;

import com.jayway.jsonpath.JsonPath;
import com.ochavada.backend.security.jwt.JwtTokenService;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserRepository;
import com.ochavada.backend.user.UserRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Utilidades para tests de la API: tokens por rol (con usuarios reales en base de datos, para que las
 * claves ajenas de auditoría sean válidas), peticiones JSON y lectura de respuestas.
 */
public abstract class AbstractApiTest extends AbstractIntegrationTest {

    private static final Map<UserRole, UUID> USERS = new EnumMap<>(UserRole.class);

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private AppUserRepository userRepository;

    protected String token(UserRole role) {
        return jwtTokenService.issueStaffAccessToken(userId(role), "user@test.local", "Usuario", role).value();
    }

    /** Usuario real (en base de datos) que respalda los tokens del rol indicado. */
    protected UUID userId(UserRole role) {
        synchronized (USERS) {
            return USERS.computeIfAbsent(role, r -> userRepository.save(AppUser.create(
                    r.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.local",
                    "{noop}no-se-usa-en-estos-tests", "Usuario " + r, r, Instant.now())).getId());
        }
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, UserRole role) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(role)));
    }

    protected ResultActions postJson(String url, String json, UserRole role) throws Exception {
        return perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json), role);
    }

    protected ResultActions putJson(String url, String json, UserRole role) throws Exception {
        return perform(MockMvcRequestBuilders.put(url).contentType(MediaType.APPLICATION_JSON).content(json), role);
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Madrid"));
    }

    /** Primer lunes a partir de {@code today() + daysAhead}: fechas futuras con día de la semana conocido. */
    protected static LocalDate mondayAfter(int daysAhead) {
        LocalDate date = today().plusDays(daysAhead);
        while (date.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date;
    }
}
