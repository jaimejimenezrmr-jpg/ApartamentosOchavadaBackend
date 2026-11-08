package com.ochavada.backend.ses.client;

/**
 * Error transitorio de comunicación con SES.HOSPEDAJES (red, HTTP 5xx, respuesta ilegible): el envío
 * debe reintentarse más tarde.
 */
public class SesTransportException extends RuntimeException {

    public SesTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
