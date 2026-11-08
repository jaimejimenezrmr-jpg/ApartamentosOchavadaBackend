package com.ochavada.backend.payment.gateway;

import com.ochavada.backend.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** 502: la pasarela de pago no respondió o rechazó la operación técnica (no un pago denegado). */
public class PaymentGatewayException extends ApiException {

    public PaymentGatewayException(String detail, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "payment.provider_error", "Error en la pasarela de pago", detail);
        initCause(cause);
    }
}
