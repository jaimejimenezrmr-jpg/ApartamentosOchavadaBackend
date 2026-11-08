package com.ochavada.backend.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Excepción de negocio que se traduce directamente en un Problem Detail (RFC 7807).
 * <p>
 * {@code code} es un identificador estable y legible por máquina (p. ej. {@code reservation.overlap})
 * que el frontend puede usar para mostrar mensajes traducidos; también forma el URI {@code type}.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final transient Map<String, Object> properties;

    protected ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, Map.of());
    }

    protected ApiException(HttpStatus status, String code, String title, String detail,
                           Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    /** Propiedades adicionales (extension members de RFC 7807) incluidas en la respuesta. */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
