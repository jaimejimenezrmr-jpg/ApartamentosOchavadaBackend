package com.ochavada.backend.auth;

import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purga diaria de refresh tokens caducados. Se conservan 30 días tras su caducidad para poder
 * investigar incidentes de seguridad (detección de reutilización).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(30);

    private final RefreshTokenRepository repository;
    private final Clock clock;

    public RefreshTokenCleanupJob(RefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.security.refresh-token-cleanup-cron:0 30 4 * * *}", zone = "Europe/Madrid")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY));
        if (deleted > 0) {
            log.info("Purgados {} refresh tokens caducados", deleted);
        }
    }
}
