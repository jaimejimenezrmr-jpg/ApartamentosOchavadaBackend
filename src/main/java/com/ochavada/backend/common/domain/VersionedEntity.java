package com.ochavada.backend.common.domain;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Base de las entidades editables desde el backoffice: id UUID, marcas de auditoría y versión para
 * bloqueo optimista (columnas {@code id, created_at, updated_at, version}).
 */
@Getter
@MappedSuperclass
public abstract class VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Comprueba que el cliente modifica la misma versión que leyó. Evita que dos personas del
     * backoffice se pisen cambios sin saberlo (lost update).
     */
    public void assertVersion(Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("validation.version_required",
                    "Debe indicar la versión del registro que está modificando");
        }
        if (expectedVersion != version) {
            throw new ConflictException("data.concurrent_modification",
                    "El registro fue modificado por otro usuario. Recargue los datos e inténtelo de nuevo",
                    Map.of("currentVersion", version));
        }
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        return id != null && id.equals(((VersionedEntity) other).getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
