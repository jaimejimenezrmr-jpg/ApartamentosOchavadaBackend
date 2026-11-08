package com.ochavada.backend.integration.credential;

import com.ochavada.backend.common.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Tokens OAuth de un proveedor, cifrados con {@link com.ochavada.backend.common.crypto.CredentialCipher}. */
@Entity
@Table(name = "integration_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IntegrationCredential extends VersionedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private CredentialProvider provider;

    @Column(name = "access_token_encrypted", columnDefinition = "text")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "text")
    private String refreshTokenEncrypted;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(name = "scope", length = 500)
    private String scope;

    public IntegrationCredential(CredentialProvider provider) {
        this.provider = provider;
    }

    public void store(String encryptedAccessToken, String encryptedRefreshToken, Instant accessExpiresAt, String scope) {
        this.accessTokenEncrypted = encryptedAccessToken;
        this.refreshTokenEncrypted = encryptedRefreshToken;
        this.accessTokenExpiresAt = accessExpiresAt;
        this.scope = scope;
    }

    public void invalidateAccessToken() {
        this.accessTokenExpiresAt = Instant.EPOCH;
    }

    public enum CredentialProvider {
        TTLOCK, BEDS24
    }
}
