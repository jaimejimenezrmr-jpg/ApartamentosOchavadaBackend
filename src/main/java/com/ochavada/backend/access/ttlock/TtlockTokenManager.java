package com.ochavada.backend.access.ttlock;

import com.ochavada.backend.access.ttlock.TtlockDtos.TokenResponse;
import com.ochavada.backend.common.crypto.CredentialCipher;
import com.ochavada.backend.integration.credential.IntegrationCredential;
import com.ochavada.backend.integration.credential.IntegrationCredential.CredentialProvider;
import com.ochavada.backend.integration.credential.IntegrationCredentialRepository;
import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Token OAuth de TTLock: se guarda cifrado en base de datos (para sobrevivir a reinicios y compartirse
 * entre instancias) y en memoria. Se renueva con el refresh token antes de caducar; si este falla, se
 * vuelve a pedir con usuario y contraseña.
 */
@Slf4j
@Component
public class TtlockTokenManager {

    private static final CredentialProvider PROVIDER = CredentialProvider.TTLOCK;
    private static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private final TtlockApiClient client;
    private final TtlockProperties properties;
    private final IntegrationCredentialRepository repository;
    private final CredentialCipher cipher;
    private final TransactionTemplate tx;
    private final Clock clock;

    private volatile CachedToken cached;

    public TtlockTokenManager(TtlockApiClient client, TtlockProperties properties,
                              IntegrationCredentialRepository repository, CredentialCipher cipher,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.repository = repository;
        this.cipher = cipher;
        // Transacción propia: el token se guarda o invalida aunque la operación que lo usa se revierta.
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Access token vigente (lo renueva si hace falta). */
    public String accessToken() {
        CachedToken current = cached;
        if (current != null && current.isFresh(clock.instant(), properties.tokenRefreshMargin())) {
            return current.value();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.isFresh(clock.instant(), properties.tokenRefreshMargin())) {
                return current.value();
            }
            cached = loadOrRefresh();
            return cached.value();
        }
    }

    /** Descarta el token (TTLock lo rechazó): la próxima llamada obtendrá uno nuevo. */
    public synchronized void invalidate() {
        cached = null;
        tx.executeWithoutResult(s -> repository.findForUpdate(PROVIDER).ifPresent(IntegrationCredential::invalidateAccessToken));
    }

    private CachedToken loadOrRefresh() {
        ensureCredentialRow();
        return tx.execute(s -> {
            // Bloqueo de fila: si otra instancia está renovando, se espera y se reutiliza su token.
            IntegrationCredential credential = repository.findForUpdate(PROVIDER).orElseThrow();
            Instant now = clock.instant();
            if (credential.getAccessTokenEncrypted() != null && credential.getAccessTokenExpiresAt() != null
                    && credential.getAccessTokenExpiresAt().minus(properties.tokenRefreshMargin()).isAfter(now)) {
                return new CachedToken(cipher.decrypt(credential.getAccessTokenEncrypted()),
                        credential.getAccessTokenExpiresAt());
            }
            TokenResponse token = null;
            if (credential.getRefreshTokenEncrypted() != null) {
                token = request(Map.of("clientId", properties.clientId(), "clientSecret", properties.clientSecret(),
                        "grant_type", "refresh_token",
                        "refresh_token", cipher.decrypt(credential.getRefreshTokenEncrypted())), false);
            }
            if (token == null) {
                Map<String, Object> form = new LinkedHashMap<>();
                form.put("clientId", properties.clientId());
                form.put("clientSecret", properties.clientSecret());
                form.put("username", properties.username());
                form.put("password", md5(properties.password()));
                token = request(form, true);
            }
            Instant expiresAt = now.plus(token.expiresIn() == null ? DEFAULT_TTL : Duration.ofSeconds(token.expiresIn()));
            credential.store(cipher.encrypt(token.accessToken()),
                    token.refreshToken() == null ? credential.getRefreshTokenEncrypted() : cipher.encrypt(token.refreshToken()),
                    expiresAt, token.scope());
            log.info("Token de TTLock renovado; caduca el {}", expiresAt);
            return new CachedToken(token.accessToken(), expiresAt);
        });
    }

    private TokenResponse request(Map<String, ?> form, boolean failOnError) {
        try {
            TokenResponse response = client.token(form);
            if (response != null && response.isOk()) {
                return response;
            }
            String reason = response == null ? "respuesta vacía"
                    : response.errcode() + " " + (response.errmsg() != null ? response.errmsg() : response.description());
            if (failOnError) {
                throw new TtlockException("TTLock rechazó las credenciales de la cuenta (" + reason + ")",
                        response == null ? null : response.errcode(), null);
            }
            log.warn("No se pudo renovar el token de TTLock con el refresh token ({}); se usará usuario y contraseña", reason);
            return null;
        } catch (FeignException ex) {
            if (failOnError) {
                throw new TtlockException("No se pudo contactar con TTLock para autenticarse", null, ex);
            }
            log.warn("Fallo renovando el token de TTLock: {}", ex.getMessage());
            return null;
        }
    }

    private void ensureCredentialRow() {
        if (repository.findByProvider(PROVIDER).isPresent()) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> repository.saveAndFlush(new IntegrationCredential(PROVIDER)));
        } catch (DataIntegrityViolationException alreadyCreated) {
            // Otra instancia la creó a la vez.
        }
    }

    /** La API de TTLock exige la contraseña de la cuenta como MD5 en minúsculas. */
    static String md5(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isFresh(Instant now, Duration margin) {
            return expiresAt.minus(margin).isAfter(now);
        }
    }
}
