package com.ochavada.backend.access;

import com.ochavada.backend.access.LockAccessLog.DenialReason;
import com.ochavada.backend.common.error.ApiException;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 403 con el motivo concreto de la denegación (la web muestra un mensaje útil al huésped). */
public class AccessDeniedProblem extends ApiException {

    public AccessDeniedProblem(DenialReason reason, String detail) {
        super(HttpStatus.FORBIDDEN, "access." + reason.name().toLowerCase(Locale.ROOT), "Apertura no permitida",
                detail, Map.of("reason", reason.name()));
    }
}
