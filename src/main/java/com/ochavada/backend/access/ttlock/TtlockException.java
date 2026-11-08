package com.ochavada.backend.access.ttlock;

import com.ochavada.backend.common.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 502: TTLock no respondió o devolvió un error (cerradura sin conexión, gateway ocupado, credenciales...). */
public class TtlockException extends ApiException {

    private final Integer providerErrorCode;

    public TtlockException(String detail, Integer providerErrorCode, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "access.lock_provider_error", "Error de la cerradura", detail,
                properties(providerErrorCode));
        this.providerErrorCode = providerErrorCode;
        if (cause != null) {
            initCause(cause);
        }
    }

    public Integer getProviderErrorCode() {
        return providerErrorCode;
    }

    private static Map<String, Object> properties(Integer code) {
        Map<String, Object> props = new HashMap<>();
        if (code != null) {
            props.put("providerErrorCode", code);
        }
        return props;
    }
}
