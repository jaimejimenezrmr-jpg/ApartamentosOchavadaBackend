package com.ochavada.backend.common.error;

import org.springframework.http.HttpStatus;

/** 401: credencial de integración (p. ej. secreto de un webhook) ausente o no válida. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String code, String detail) {
        super(HttpStatus.UNAUTHORIZED, code, "No autorizado", detail);
    }
}
