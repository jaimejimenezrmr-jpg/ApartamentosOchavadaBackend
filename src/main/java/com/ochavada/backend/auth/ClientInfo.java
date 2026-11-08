package com.ochavada.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Metadatos del cliente asociados a una sesión, para auditoría.
 * <p>
 * La IP se toma de {@code getRemoteAddr()}: con {@code server.forward-headers-strategy=framework}
 * Spring la resuelve desde {@code X-Forwarded-For} establecido por el proxy inverso (Caddy).
 */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
