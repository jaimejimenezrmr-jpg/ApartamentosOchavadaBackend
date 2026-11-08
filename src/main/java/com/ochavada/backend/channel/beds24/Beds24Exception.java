package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.common.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 502: Beds24 no respondió o rechazó la operación. */
public class Beds24Exception extends ApiException {

    private final Integer httpStatus;

    public Beds24Exception(String detail, Integer httpStatus, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "channel.beds24_error", "Error del channel manager", detail, properties(httpStatus));
        this.httpStatus = httpStatus;
        if (cause != null) {
            initCause(cause);
        }
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    private static Map<String, Object> properties(Integer status) {
        Map<String, Object> props = new HashMap<>();
        if (status != null) {
            props.put("providerStatus", status);
        }
        return props;
    }
}
