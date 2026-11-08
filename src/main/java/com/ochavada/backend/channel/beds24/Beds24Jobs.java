package com.ochavada.backend.channel.beds24;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesos periódicos de Beds24: sondeo de reservas modificadas (red de seguridad de los webhooks) y
 * resincronización completa nocturna del inventario (corrige cualquier deriva, p. ej. cambios hechos a
 * mano en el extranet de Beds24 o de una OTA).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class Beds24Jobs {

    private final Beds24Properties properties;
    private final Beds24TokenManager tokenManager;
    private final Beds24PollService pollService;
    private final Beds24SyncListener syncListener;
    private final TransactionTemplate tx;

    public Beds24Jobs(Beds24Properties properties, Beds24TokenManager tokenManager, Beds24PollService pollService,
                      Beds24SyncListener syncListener, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.pollService = pollService;
        this.syncListener = syncListener;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.jobs.beds24-poll-interval:10m}", initialDelayString = "60s")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            pollService.pollModified();
        } catch (RuntimeException ex) {
            log.warn("Beds24: sondeo de reservas fallido: {}", ex.getMessage());
        }
    }

    @Scheduled(cron = "${app.jobs.beds24-full-sync-cron:0 30 3 * * *}", zone = "${app.property.time-zone:Europe/Madrid}")
    public void fullSync() {
        if (!properties.enabled() || !tokenManager.isConnected()) {
            return;
        }
        Integer roomTypes = tx.execute(s -> syncListener.enqueueFullSync());
        log.info("Beds24: resincronización completa encolada para {} tipologías", roomTypes);
    }
}
