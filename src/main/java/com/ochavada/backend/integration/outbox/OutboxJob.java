package com.ochavada.backend.integration.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Entrega periódica del outbox. Se desactiva con {@code app.jobs.enabled=false} (tests). */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxJob {

    private static final int BATCH = 50;

    private final OutboxProcessor processor;

    public OutboxJob(OutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.jobs.outbox-interval:10s}", initialDelayString = "15s")
    public void deliver() {
        processor.processDue(BATCH);
    }
}
