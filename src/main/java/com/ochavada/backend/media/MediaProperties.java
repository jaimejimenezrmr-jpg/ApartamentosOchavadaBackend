package com.ochavada.backend.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Almacenamiento de fotos ({@code app.media.*}).
 *
 * @param storagePath directorio donde se guardan los ficheros (en Docker, un volumen persistente)
 * @param maxBytes    tamaño máximo de cada foto; el front las reduce antes de subirlas
 */
@Validated
@ConfigurationProperties("app.media")
public record MediaProperties(
        @NotNull @DefaultValue("/app/data/media") Path storagePath,
        @Min(10_000) @Max(20_000_000) @DefaultValue("10000000") int maxBytes) {
}
