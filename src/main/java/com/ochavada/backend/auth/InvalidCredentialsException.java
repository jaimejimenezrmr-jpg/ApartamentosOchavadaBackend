package com.ochavada.backend.auth;

import com.ochavada.backend.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 401 en el login. El mensaje es deliberadamente genérico: no revela si el email existe, si la
 * cuenta está bloqueada o deshabilitada (prevención de enumeración de usuarios).
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials", "Credenciales no válidas",
                "Email o contraseña incorrectos");
    }
}
