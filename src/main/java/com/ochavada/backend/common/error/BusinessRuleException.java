package com.ochavada.backend.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 422: la petición es sintácticamente válida pero viola una regla de negocio. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, "Regla de negocio incumplida", detail);
    }

    public BusinessRuleException(String code, String detail, Map<String, Object> properties) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, "Regla de negocio incumplida", detail, properties);
    }
}
