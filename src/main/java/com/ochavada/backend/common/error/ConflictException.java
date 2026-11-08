package com.ochavada.backend.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 409: la operación entra en conflicto con el estado actual (solapamientos, duplicados...). */
public class ConflictException extends ApiException {

    public ConflictException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, "Conflicto con el estado actual", detail);
    }

    public ConflictException(String code, String detail, Map<String, Object> properties) {
        super(HttpStatus.CONFLICT, code, "Conflicto con el estado actual", detail, properties);
    }
}
