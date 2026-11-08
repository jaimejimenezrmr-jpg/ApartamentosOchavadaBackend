package com.ochavada.backend.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Responde a peticiones no autenticadas (o con bearer token inválido) con un 401 RFC 7807.
 * <p>
 * Delega la serialización en el {@link HandlerExceptionResolver} de Spring MVC para que las
 * excepciones lanzadas dentro de la cadena de filtros produzcan exactamente el mismo
 * {@code application/problem+json} que las lanzadas desde los controladores
 * ({@link com.ochavada.backend.common.error.GlobalExceptionHandler}).
 * Añade además la cabecera {@code WWW-Authenticate} de RFC 6750.
 */
@Component
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemDetailsAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(authException));
        if (handlerExceptionResolver.resolveException(request, response, null, authException) == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
        }
    }

    private static String wwwAuthenticate(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauth2 && oauth2.getError() != null) {
            return "Bearer error=\"" + oauth2.getError().getErrorCode() + "\"";
        }
        return "Bearer";
    }
}
