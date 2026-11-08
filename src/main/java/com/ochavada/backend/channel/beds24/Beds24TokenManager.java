package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.beds24.Beds24Dtos.TokenResponse;
import com.ochavada.backend.common.crypto.CredentialCipher;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ServiceUnavailableException;
import com.ochavada.backend.integration.credential.IntegrationCredential;
import com.ochavada.backend.integration.credential.IntegrationCredential.CredentialProvider;
import com.ochavada.backend.integration.credential.IntegrationCredentialRepository;
import feign.FeignException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tokens de Beds24: el refresh token (permanente mientras se use) se guarda cifrado; el access token
 * (≈24 h) se renueva con margen y se comparte entre instancias a través de la base de datos.
 */
@Slf4j
@Component
public class Beds24TokenManager {

    private static final CredentialProvider PROVIDER = CredentialProvider.BEDS24;
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TTL = Duration.ofHours(23);
    private static final String DEVICE_NAME = "Apartamentos Ochavada PMS";

    private final Beds24ApiClient client;
    private final Beds24Properties properties;
    private final IntegrationCredentialRepository repository;
    private final CredentialCipher cipher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private volatile Cached cached;

    public Beds24TokenManager(@Lazy Beds24ApiClient client, Beds24Properties properties,
                              IntegrationCredentialRepository repository, CredentialCipher cipher,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.repository = repository;
        this.cipher = cipher;
        // Transacción propia: los tokens se guardan o invalidan aunque la operación que los usa se revierta
        // (p. ej. un 401 dentro de un manejador del outbox que después falla).
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Canjea un código de invitación de Beds24 y guarda el refresh token resultante. */
    public void setup(String inviteCode) {
        TokenResponse response;
        try {
            response = client.setup(inviteCode.trim(), DEVICE_NAME);
        } catch (FeignException ex) {
            throw new BadRequestException("beds24.invalid_invite_code",
                    "Beds24 rechazó el código de invitación (caduca a las 24 h; genere uno nuevo)");
        }
        if (response == null || response.refreshToken() == null) {
            throw new BadRequestException("beds24.invalid_invite_code", "Beds24 no devolvió un refresh token");
        }
        ensureRow();
        Instant expiresAt = clock.instant().plus(ttl(response));
        tx.executeWithoutResult(s -> repository.findForUpdate(PROVIDER).orElseThrow().store(
                response.token() == null ? null : cipher.encrypt(response.token()),
                cipher.encrypt(response.refreshToken()), response.token() == null ? Instant.EPOCH : expiresAt, null));
        cached = response.token() == null ? null : new Cached(response.token(), expiresAt);
        log.info("Beds24 conectado: refresh token almacenado");
    }

    public String accessToken() {
        Cached current = cached;
        if (current != null && current.expiresAt().minus(REFRESH_MARGIN).isAfter(clock.instant())) {
            return current.value();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.expiresAt().minus(REFRESH_MARGIN).isAfter(clock.instant())) {
                return current.value();
            }
            cached = loadOrRefresh();
            return cached.value();
        }
    }

    public synchronized void invalidate() {
        cached = null;
        tx.executeWithoutResult(s -> repository.findForUpdate(PROVIDER).ifPresent(IntegrationCredential::invalidateAccessToken));
    }

    public boolean isConnected() {
        return properties.enabled() && (notBlank(properties.refreshToken())
                || repository.findByProvider(PROVIDER).map(c -> c.getRefreshTokenEncrypted() != null).orElse(false));
    }

    private Cached loadOrRefresh() {
        ensureRow();
        return tx.execute(s -> {
            IntegrationCredential credential = repository.findForUpdate(PROVIDER).orElseThrow();
            Instant now = clock.instant();
            if (credential.getAccessTokenEncrypted() != null && credential.getAccessTokenExpiresAt() != null
                    && credential.getAccessTokenExpiresAt().minus(REFRESH_MARGIN).isAfter(now)) {
                return new Cached(cipher.decrypt(credential.getAccessTokenEncrypted()), credential.getAccessTokenExpiresAt());
            }
            String refreshToken = credential.getRefreshTokenEncrypted() != null
                    ? cipher.decrypt(credential.getRefreshTokenEncrypted()) : properties.refreshToken();
            if (!notBlank(refreshToken)) {
                throw new ServiceUnavailableException("beds24.not_connected", "Beds24 no conectado",
                        "Conecte Beds24 con un código de invitación o configure BEDS24_REFRESH_TOKEN");
            }
            TokenResponse response;
            try {
                response = client.token(refreshToken);
            } catch (FeignException ex) {
                throw new Beds24Exception("Beds24 rechazó el refresh token (" + ex.status() + ")", ex.status(), ex);
            }
            if (response == null || !notBlank(response.token())) {
                throw new Beds24Exception("Beds24 no devolvió access token", null, null);
            }
            Instant expiresAt = now.plus(ttl(response));
            credential.store(cipher.encrypt(response.token()), cipher.encrypt(refreshToken), expiresAt, null);
            return new Cached(response.token(), expiresAt);
        });
    }

    private void ensureRow() {
        if (repository.findByProvider(PROVIDER).isPresent()) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> repository.saveAndFlush(new IntegrationCredential(PROVIDER)));
        } catch (DataIntegrityViolationException alreadyCreated) {
            // Creada en paralelo por otra instancia.
        }
    }

    private static Duration ttl(TokenResponse response) {
        return response.expiresIn() == null || response.expiresIn() <= 0 ? DEFAULT_TTL : Duration.ofSeconds(response.expiresIn());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record Cached(String value, Instant expiresAt) {
    }
}
