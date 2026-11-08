package com.ochavada.backend.ses;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Consulta periódica del resultado de los lotes enviados a SES.HOSPEDAJES. */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SesBatchJob {

    private final SesBatchService service;

    public SesBatchJob(SesBatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.jobs.ses-batch-interval:60s}", initialDelayString = "45s")
    public void check() {
        service.checkSubmitted();
    }
}
