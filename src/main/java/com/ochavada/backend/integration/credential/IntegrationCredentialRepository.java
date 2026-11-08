package com.ochavada.backend.integration.credential;

import com.ochavada.backend.integration.credential.IntegrationCredential.CredentialProvider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationCredentialRepository extends JpaRepository<IntegrationCredential, UUID> {

    Optional<IntegrationCredential> findByProvider(CredentialProvider provider);

    /** Serializa la renovación del token entre instancias: solo una pide uno nuevo al proveedor. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from IntegrationCredential c where c.provider = :provider")
    Optional<IntegrationCredential> findForUpdate(@Param("provider") CredentialProvider provider);
}
