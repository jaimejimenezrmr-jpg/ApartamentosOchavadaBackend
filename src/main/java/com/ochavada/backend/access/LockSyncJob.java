package com.ochavada.backend.access;

import com.ochavada.backend.access.dto.LockDtos.SyncResult;
import com.ochavada.backend.access.ttlock.TtlockGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sincronización diaria del nivel de batería de las cerraduras. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LockSyncJob {

    private final LockAdminService service;
    private final TtlockGateway gateway;

    public LockSyncJob(LockAdminService service, TtlockGateway gateway) {
        this.service = service;
        this.gateway = gateway;
    }

    @Scheduled(cron = "${app.jobs.lock-sync-cron:0 0 6 * * *}", zone = "Europe/Madrid")
    public void sync() {
        if (!gateway.isConfigured()) {
            return;
        }
        SyncResult result = service.syncBatteries();
        log.info("Sincronización de cerraduras: {} actualizadas, {} con error", result.updated(), result.failed());
    }
}
