package com.ochavada.backend.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * Construye y normaliza los Problem Details (RFC 7807 / RFC 9457) de toda la API:
 * <pre>
 * {
 *   "type": "https://apartamentosochavada.com/problems/auth.invalid_credentials",
 *   "title": "Credenciales no válidas",
 *   "status": 401,
 *   "detail": "Email o contraseña incorrectos",
 *   "instance": "/api/v1/auth/login",
 *   "code": "auth.invalid_credentials",
 *   "timestamp": "2026-11-07T18:00:00Z"
 * }
 * </pre>
 */
@Component
public class ProblemDetailFactory {

    public static final String CODE = "code";
    public static final String TIMESTAMP = "timestamp";
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private final URI typeBaseUri;
    private final Clock clock;

    public ProblemDetailFactory(ProblemProperties properties, Clock clock) {
        String base = properties.typeBaseUri().toString();
        this.typeBaseUri = URI.create(base.endsWith("/") ? base : base + "/");
        this.clock = clock;
    }

    public ProblemDetail create(HttpStatusCode status, String code, String title, String detail,
                                Map<String, Object> properties, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(typeBaseUri.resolve(code));
        properties.forEach(problem::setProperty);
        return complete(problem, code, request);
    }

    /**
     * Completa un ProblemDetail generado por Spring (p. ej. 405, 415, 404 de recurso inexistente) con
     * {@code type}, {@code code}, {@code instance} y {@code timestamp} coherentes con el resto de la API.
     */
    public ProblemDetail enrich(ProblemDetail problem, WebRequest request) {
        String code = problem.getProperties() != null && problem.getProperties().get(CODE) instanceof String c
                ? c
                : defaultCode(problem.getStatus());
        if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
            problem.setType(typeBaseUri.resolve(code));
        }
        return complete(problem, code, request);
    }

    private ProblemDetail complete(ProblemDetail problem, String code, WebRequest request) {
        problem.setProperty(CODE, code);
        problem.setProperty(TIMESTAMP, clock.instant().toString());
        if (problem.getInstance() == null) {
            String path = requestPath(request);
            if (path != null) {
                problem.setInstance(URI.create(path));
            }
        }
        return problem;
    }

    private static String defaultCode(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known != null ? "http." + known.name().toLowerCase(Locale.ROOT) : "http." + status;
    }

    private static String requestPath(WebRequest request) {
        if (request instanceof NativeWebRequest nativeRequest) {
            HttpServletRequest servletRequest = nativeRequest.getNativeRequest(HttpServletRequest.class);
            if (servletRequest != null) {
                return servletRequest.getRequestURI();
            }
        }
        return null;
    }
}
