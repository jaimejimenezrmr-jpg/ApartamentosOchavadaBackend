package com.ochavada.backend.security.web;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Endpoints accesibles sin autenticación. Se usan tanto en las reglas de autorización como en el
 * resolvedor de bearer token, de forma que un token caducado enviado por error a un endpoint público
 * (p. ej. el catálogo) no provoque un 401.
 */
public final class PublicEndpoints {

    public static final String LOGIN = "/api/v1/auth/login";
    public static final String REFRESH = "/api/v1/auth/refresh";
    public static final String LOGOUT = "/api/v1/auth/logout";

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    public static final RequestMatcher MATCHER = new OrRequestMatcher(List.of(
            PATHS.matcher(HttpMethod.POST, LOGIN),
            PATHS.matcher(HttpMethod.POST, REFRESH),
            PATHS.matcher(HttpMethod.POST, LOGOUT),
            // Catálogo, disponibilidad y checkout de la web pública
            PATHS.matcher("/api/v1/public/**"),
            // Webhooks de Stripe / Beds24 / TTLock: se autentican por firma propia del proveedor
            PATHS.matcher("/api/v1/webhooks/**"),
            // Canje del enlace mágico del huésped por un token de portal
            PATHS.matcher("/api/v1/guest/auth/**"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health/**"),
            PATHS.matcher(HttpMethod.GET, "/actuator/info"),
            PATHS.matcher("/error")));

    private PublicEndpoints() {
    }
}
