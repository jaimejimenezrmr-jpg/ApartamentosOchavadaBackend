package com.ochavada.backend.guestportal;

import com.ochavada.backend.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 401 genérico para enlaces de huésped inexistentes, caducados o revocados: no se distingue el motivo
 * para no dar pistas a quien pruebe enlaces al azar.
 */
public class InvalidGuestAccessException extends ApiException {

    public InvalidGuestAccessException() {
        super(HttpStatus.UNAUTHORIZED, "guest.invalid_access", "Acceso no válido",
                "El enlace de acceso no es válido o ha caducado. Contacte con el alojamiento si lo necesita");
    }
}
