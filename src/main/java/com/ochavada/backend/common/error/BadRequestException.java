package com.ochavada.backend.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 400: petición incorrecta que no se detecta con anotaciones de Bean Validation. */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String detail) {
        super(HttpStatus.BAD_REQUEST, code, "Petición no válida", detail);
    }

    public BadRequestException(String code, String detail, Map<String, Object> properties) {
        super(HttpStatus.BAD_REQUEST, code, "Petición no válida", detail, properties);
    }
}
