package com.ochavada.backend.media;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.security.CurrentUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fotos del centro de operaciones. El fichero se escribe en disco y sus metadatos en la base de datos dentro
 * de la transacción del llamante: si esta se deshace, el fichero se borra; al borrar una foto, el fichero se
 * elimina solo cuando la transacción se confirma.
 * <p>
 * El tipo se decide por la firma del contenido (JPEG, PNG o WebP), nunca por el nombre ni por la cabecera
 * que envía el navegador.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    private final MediaFileRepository repository;
    private final MediaProperties properties;
    private final Clock clock;

    public MediaService(MediaFileRepository repository, MediaProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MediaFile store(MediaOwnerType ownerType, UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("media.empty", "No se ha recibido ninguna foto");
        }
        if (file.getSize() > properties.maxBytes()) {
            throw new BadRequestException("media.too_large", "La foto supera el tamaño máximo permitido",
                    Map.of("maxBytes", properties.maxBytes()));
        }
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readNBytes(properties.maxBytes() + 1);
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer la foto recibida", ex);
        }
        if (content.length > properties.maxBytes()) {
            throw new BadRequestException("media.too_large", "La foto supera el tamaño máximo permitido",
                    Map.of("maxBytes", properties.maxBytes()));
        }
        ImageType type = ImageType.detect(content);
        if (type == null) {
            throw new BadRequestException("media.unsupported_type", "Solo se admiten fotos JPEG, PNG o WebP");
        }

        Instant now = clock.instant();
        String key = FOLDER.format(now) + "/" + UUID.randomUUID() + "." + type.extension;
        Path target = resolve(key);
        write(target, content);
        afterRollback(() -> deleteQuietly(target));

        return repository.save(new MediaFile(ownerType, ownerId, type.contentType, content.length, sha256(content), key,
                CurrentUser.staffUserId().orElse(null), now));
    }

    @Transactional(readOnly = true)
    public List<MediaFile> list(MediaOwnerType ownerType, UUID ownerId) {
        return repository.findByOwnerTypeAndOwnerIdOrderByCreatedAtAsc(ownerType, ownerId);
    }

    @Transactional(readOnly = true)
    public List<MediaFile> list(MediaOwnerType ownerType, Collection<UUID> ownerIds) {
        return ownerIds.isEmpty() ? List.of()
                : repository.findByOwnerTypeAndOwnerIdInOrderByCreatedAtAsc(ownerType, ownerIds);
    }

    @Transactional(readOnly = true)
    public long count(MediaOwnerType ownerType, UUID ownerId) {
        return repository.countByOwnerTypeAndOwnerId(ownerType, ownerId);
    }

    /** Borra una foto comprobando que pertenece al registro indicado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(MediaOwnerType ownerType, UUID ownerId, UUID mediaId) {
        MediaFile media = repository.findById(mediaId)
                .filter(m -> m.getOwnerType() == ownerType && m.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("foto", mediaId));
        repository.delete(media);
        Path path = resolve(media.getStorageKey());
        afterCommit(() -> deleteQuietly(path));
    }

    @Transactional(readOnly = true)
    public StoredMedia load(UUID mediaId) {
        MediaFile media = repository.findById(mediaId).orElseThrow(() -> new ResourceNotFoundException("foto", mediaId));
        Path path = resolve(media.getStorageKey());
        if (!Files.isRegularFile(path)) {
            log.error("Falta en disco el fichero de la foto {} ({})", mediaId, media.getStorageKey());
            throw new ResourceNotFoundException("foto", mediaId);
        }
        return new StoredMedia(media, path);
    }

    private Path resolve(String key) {
        Path root = properties.storagePath().toAbsolutePath().normalize();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalStateException("Ruta de foto fuera del almacenamiento: " + key);
        }
        return path;
    }

    private static void write(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, content);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo guardar la foto en " + target, ex);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("No se pudo borrar el fichero {}: {}", path, ex.getMessage());
        }
    }

    private static void afterRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Foto lista para servir. */
    public record StoredMedia(MediaFile media, Path path) {
    }

    enum ImageType {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

        final String contentType;
        final String extension;

        ImageType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        static ImageType detect(byte[] c) {
            if (c.length >= 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8 && (c[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (c.length >= 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G'
                    && c[4] == 0x0D && c[5] == 0x0A && c[6] == 0x1A && c[7] == 0x0A) {
                return PNG;
            }
            if (c.length >= 12 && c[0] == 'R' && c[1] == 'I' && c[2] == 'F' && c[3] == 'F'
                    && c[8] == 'W' && c[9] == 'E' && c[10] == 'B' && c[11] == 'P') {
                return WEBP;
            }
            return null;
        }
    }
}
