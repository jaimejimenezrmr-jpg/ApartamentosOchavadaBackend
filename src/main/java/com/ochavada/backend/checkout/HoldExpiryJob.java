package com.ochavada.backend.checkout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Caducidad periódica de retenciones. Se desactiva con {@code app.jobs.enabled=false} (tests). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {

    private static final int BATCH = 100;

    private final HoldExpiryService service;

    public HoldExpiryJob(HoldExpiryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.jobs.hold-expiry-interval:60s}", initialDelayString = "30s")
    public void expire() {
        service.expireDueHolds(BATCH);
    }
}
