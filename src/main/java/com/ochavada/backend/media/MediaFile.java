package com.ochavada.backend.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Metadatos de una foto guardada en disco. Inmutable: una foto se sube o se borra, no se edita. */
@Entity
@Immutable
@Table(name = "media_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 30)
    private MediaOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 200)
    private String storageKey;

    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    MediaFile(MediaOwnerType ownerType, UUID ownerId, String contentType, int sizeBytes, String sha256,
              String storageKey, UUID uploadedByUserId, Instant createdAt) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.uploadedByUserId = uploadedByUserId;
        this.createdAt = createdAt;
    }
}
