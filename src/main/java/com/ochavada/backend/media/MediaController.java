package com.ochavada.backend.media;

import java.time.Duration;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Descarga de fotos para el personal. Las fotos se suben desde el registro al que pertenecen (limpieza,
 * incidencia, objeto olvidado). Son inmutables: se pueden cachear en el navegador, pero solo en privado.
 */
@RestController
@RequestMapping("/api/v1/backoffice/media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        MediaService.StoredMedia stored = service.load(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.media().getContentType()))
                .contentLength(stored.media().getSizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(id + extension(stored)).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(stored.path()));
    }

    private static String extension(MediaService.StoredMedia stored) {
        String key = stored.media().getStorageKey();
        return key.substring(key.lastIndexOf('.'));
    }
}
