package com.ochavada.backend.auth;

import com.ochavada.backend.common.error.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 400: la contraseña actual indicada al cambiarla no es correcta. */
public class InvalidCurrentPasswordException extends ApiException {

    public InvalidCurrentPasswordException() {
        super(HttpStatus.BAD_REQUEST, "auth.invalid_current_password", "Contraseña incorrecta",
                "La contraseña actual no es correcta",
                Map.of("errors", List.of(Map.of("field", "currentPassword", "message", "No es correcta"))));
    }
}
