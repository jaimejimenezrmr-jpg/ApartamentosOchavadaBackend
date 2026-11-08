package com.ochavada.backend.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 404: el recurso solicitado no existe (o el usuario no puede saber que existe). */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "resource.not_found", "Recurso no encontrado",
                "No existe " + resource + " con identificador " + id,
                Map.of("resource", resource));
    }
}
