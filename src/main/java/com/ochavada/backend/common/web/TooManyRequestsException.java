package com.ochavada.backend.common.web;

import com.ochavada.backend.common.error.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 429: demasiadas peticiones desde el mismo origen. */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limit.exceeded", "Demasiadas peticiones",
                "Ha realizado demasiados intentos. Espere unos minutos e inténtelo de nuevo",
                Map.of("retryAfterSeconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
