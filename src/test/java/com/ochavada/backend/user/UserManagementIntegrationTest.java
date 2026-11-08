package com.ochavada.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ochavada.backend.support.AbstractApiTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class UserManagementIntegrationTest extends AbstractApiTest {

    private static final String USERS = "/api/v1/admin/users";

    @Test
    void newUsersMustReplaceTheirTemporaryPasswordBeforeUsingTheBackoffice() throws Exception {
        String email = "recepcion-" + suffix().toLowerCase() + "@ochavada.test";
        ResultActions created = postJson(USERS, """
                {"email":" %s ","fullName":"Ana Recepción","phone":"+34 600 111 222","role":"RECEPTIONIST"}
                """.formatted(email.toUpperCase()), UserRole.ADMIN)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.user.enabled").value(true));
        String id = read(created, "$.user.id");
        String temporary = read(created, "$.temporaryPassword");
        assertThat(temporary).matches("^[A-Za-z2-9]{4}(-[A-Za-z2-9]{4}){3}$");
        assertThat(jdbc.queryForObject("select password_hash from app_user where id = ?::uuid", String.class, id))
                .doesNotContain(temporary);

        // Mismo email otra vez: conflicto.
        postJson(USERS, "{\"email\":\"%s\",\"fullName\":\"Otra\",\"role\":\"HOUSEKEEPING\"}".formatted(email), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.duplicate_email"));

        // Con la contraseña temporal solo puede ver su perfil y cambiarla.
        String pendingToken = login(email, temporary).getResponse().getContentAsString();
        String pending = JsonPath.read(pendingToken, "$.accessToken");
        assertThat((Boolean) JsonPath.read(pendingToken, "$.user.passwordChangeRequired")).isTrue();
        bearer(get("/api/v1/backoffice/dashboard"), pending).andExpect(status().isForbidden());
        bearer(get("/api/v1/auth/me"), pending).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        changePassword(pending, "incorrecta-123456", "Nueva-Clave-Segura-2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.invalid_current_password"));
        changePassword(pending, temporary, "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.weak_password"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        changePassword(pending, temporary, temporary)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth.same_password"));

        // Tokens con resolución de segundos: el anterior debe ser de un segundo previo para quedar invalidado.
        Thread.sleep(1100);
        MvcResult changed = changePassword(pending, temporary, "Nueva-Clave-Segura-2026")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(false))
                .andReturn();
        String fresh = JsonPath.read(changed.getResponse().getContentAsString(), "$.accessToken");
        assertThat(changed.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("ochavada_rt=");
        bearer(get("/api/v1/auth/me"), pending).andExpect(status().isUnauthorized());
        bearer(get("/api/v1/backoffice/dashboard"), fresh).andExpect(status().isOk());
        login(email, temporary, false);
        login(email, "Nueva-Clave-Segura-2026");
        assertThat(jdbc.queryForList("select action from audit_log where entity_id = ?", String.class, id))
                .contains("user.created", "user.password_changed");
    }

    @Test
    void disablingOrChangingTheRoleRevokesTokensImmediately() throws Exception {
        String email = "limpieza-" + suffix().toLowerCase() + "@ochavada.test";
        String id = createAndActivate(email, "HOUSEKEEPING", "Clave-Limpieza-2026");
        MvcResult session = login(email, "Clave-Limpieza-2026");
        String token = JsonPath.read(session.getResponse().getContentAsString(), "$.accessToken");
        Cookie refresh = session.getResponse().getCookie("ochavada_rt");
        bearer(get("/api/v1/backoffice/housekeeping/tasks"), token).andExpect(status().isOk());

        // Cambio de rol: el token con el rol anterior deja de valer y el siguiente refresh trae el nuevo rol.
        long version = ((Number) read(perform(get(USERS + "/" + id), UserRole.ADMIN), "$.version")).longValue();
        putJson(USERS + "/" + id, """
                {"fullName":"Luis Limpieza","phone":"","role":"RECEPTIONIST","enabled":true,"version":%d}
                """.formatted(version), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RECEPTIONIST"));
        bearer(get("/api/v1/backoffice/housekeeping/tasks"), token).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        MvcResult relogin = login(email, "Clave-Limpieza-2026");
        String receptionToken = JsonPath.read(relogin.getResponse().getContentAsString(), "$.accessToken");
        bearer(get("/api/v1/backoffice/dashboard"), receptionToken).andExpect(status().isOk());

        // Desactivación: fuera al instante y sin poder volver a entrar.
        version = ((Number) read(perform(get(USERS + "/" + id), UserRole.ADMIN), "$.version")).longValue();
        putJson(USERS + "/" + id, """
                {"fullName":"Luis Limpieza","role":"RECEPTIONIST","enabled":false,"version":%d}
                """.formatted(version), UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        bearer(get("/api/v1/backoffice/dashboard"), receptionToken).andExpect(status().isUnauthorized());
        login(email, "Clave-Limpieza-2026", false);

        // Versión obsoleta: conflicto de concurrencia.
        putJson(USERS + "/" + id, """
                {"fullName":"Luis","role":"RECEPTIONIST","enabled":true,"version":%d}
                """.formatted(version), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data.concurrent_modification"));
    }

    @Test
    void adminsCanResetPasswordsAndUnlockAccountsButNotLockThemselvesOut() throws Exception {
        String email = "recepcion2-" + suffix().toLowerCase() + "@ochavada.test";
        String id = createAndActivate(email, "RECEPTIONIST", "Clave-Recepcion-2026");

        // Tres intentos fallidos (configuración de tests) bloquean la cuenta.
        for (int i = 0; i < 3; i++) {
            login(email, "incorrecta-" + i, false);
        }
        perform(get(USERS + "/" + id), UserRole.ADMIN).andExpect(jsonPath("$.locked").value(true));
        login(email, "Clave-Recepcion-2026", false);
        postJson(USERS + "/" + id + "/unlock", "", UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        login(email, "Clave-Recepcion-2026");

        // Reseteo: nueva contraseña temporal; la anterior deja de funcionar.
        ResultActions reset = postJson(USERS + "/" + id + "/reset-password", "", UserRole.ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(true));
        login(email, "Clave-Recepcion-2026", false);
        login(email, read(reset, "$.temporaryPassword"));

        // Un administrador no puede desactivarse, quitarse el rol ni resetearse a sí mismo.
        String adminId = userId(UserRole.ADMIN).toString();
        long adminVersion = ((Number) read(perform(get(USERS + "/" + adminId), UserRole.ADMIN), "$.version")).longValue();
        putJson(USERS + "/" + adminId, """
                {"fullName":"Admin","role":"ADMIN","enabled":false,"version":%d}
                """.formatted(adminVersion), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.cannot_disable_self"));
        putJson(USERS + "/" + adminId, """
                {"fullName":"Admin","role":"RECEPTIONIST","enabled":true,"version":%d}
                """.formatted(adminVersion), UserRole.ADMIN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("user.cannot_change_own_role"));
        postJson(USERS + "/" + adminId + "/reset-password", "", UserRole.ADMIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.cannot_reset_self"));

        // Solo administración gestiona usuarios.
        perform(get(USERS), UserRole.RECEPTIONIST).andExpect(status().isForbidden());
        perform(get(USERS), UserRole.ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '%s')]".formatted(email)).exists());
    }

    // ------------------------------------------------------------------ helpers

    /** Alta + cambio de la contraseña temporal; devuelve el id. */
    private String createAndActivate(String email, String role, String password) throws Exception {
        ResultActions created = postJson(USERS, "{\"email\":\"%s\",\"fullName\":\"Usuario de prueba\",\"role\":\"%s\"}"
                .formatted(email, role), UserRole.ADMIN).andExpect(status().isCreated());
        String temporary = read(created, "$.temporaryPassword");
        String pending = JsonPath.read(login(email, temporary).getResponse().getContentAsString(),
                "$.accessToken");
        changePassword(pending, temporary, password).andExpect(status().isOk());
        return read(created, "$.user.id");
    }

    private MvcResult login(String email, String password) throws Exception {
        return login(email, password, true);
    }

    private MvcResult login(String email, String password, boolean expectSuccess) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(expectSuccess ? status().isOk() : status().isUnauthorized())
                .andReturn();
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return bearer(put("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)), token);
    }

    private ResultActions bearer(MockHttpServletRequestBuilder request,
                                 String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
