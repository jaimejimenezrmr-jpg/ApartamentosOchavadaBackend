package com.ochavada.backend.media;

import java.time.Instant;
import java.util.UUID;

/** Foto vista desde la API: el contenido se descarga en {@code /api/v1/backoffice/media/{id}}. */
public record MediaResponse(UUID id, String contentType, int sizeBytes, UUID uploadedByUserId, Instant createdAt) {

    public static MediaResponse from(MediaFile m) {
        return new MediaResponse(m.getId(), m.getContentType(), m.getSizeBytes(), m.getUploadedByUserId(), m.getCreatedAt());
    }
}
