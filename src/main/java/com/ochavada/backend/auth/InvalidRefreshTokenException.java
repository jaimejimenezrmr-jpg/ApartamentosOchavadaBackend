package com.ochavada.backend.auth;

import com.ochavada.backend.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** 401: refresh token ausente, desconocido, caducado o revocado. El cliente debe volver a iniciar sesión. */
public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "auth.invalid_refresh_token", "Sesión no válida",
                "La sesión ha caducado o no es válida. Inicie sesión de nuevo");
    }
}
