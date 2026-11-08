package com.ochavada.backend.common.error;

import org.springframework.http.HttpStatus;

/** 503: funcionalidad no disponible (integración desactivada o sin configurar). */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String code, String title, String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, title, detail);
    }
}
