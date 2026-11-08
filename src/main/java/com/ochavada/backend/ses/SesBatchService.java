package com.ochavada.backend.ses;

import com.ochavada.backend.ses.ReportEnums.Operation;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.client.SesResponse;
import com.ochavada.backend.ses.client.SesResponse.BatchResult;
import com.ochavada.backend.ses.client.SesResponse.CommunicationResult;
import com.ochavada.backend.ses.client.SesSoapClient;
import com.ochavada.backend.ses.client.SesTransportException;
import com.ochavada.backend.ses.client.SesXmlBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consulta el resultado de los lotes enviados (operación C, máximo 10 lotes por petición) y actualiza
 * cada comunicación: ACCEPTED con su código, REJECTED con el error de validación del Ministerio, o
 * reprogramada si el lote sigue en proceso.
 */
@Slf4j
@Service
public class SesBatchService {

    static final int MAX_BATCHES_PER_QUERY = 10;
    /** Tras este número de consultas sin encontrar el lote, se marca para revisión. */
    static final int MAX_CHECKS = 60;

    private final TravelerReportRepository repository;
    private final SesXmlBuilder xmlBuilder;
    private final SesSoapClient client;
    private final SesProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SesBatchService(TravelerReportRepository repository, SesXmlBuilder xmlBuilder, SesSoapClient client,
                           SesProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.xmlBuilder = xmlBuilder;
        this.client = client;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return comunicaciones cuyo estado quedó resuelto (aceptadas o rechazadas) */
    public int checkSubmitted() {
        if (!properties.isConfigured()) {
            return 0;
        }
        List<UUID> ids = repository.findBatchesToCheck(clock.instant(), Limit.of(MAX_BATCHES_PER_QUERY));
        if (ids.isEmpty()) {
            return 0;
        }
        Map<UUID, String> batchByReport = tx.execute(s -> repository.findAllById(ids).stream()
                .collect(Collectors.toMap(TravelerReport::getId, TravelerReport::getSesBatchId)));
        String query = xmlBuilder.batchQuery(batchByReport.values());
        SesResponse response;
        try {
            response = client.send(xmlBuilder.envelope("C", null, xmlBuilder.pack(query)));
        } catch (SesTransportException ex) {
            log.warn("No se pudo consultar el estado de los lotes en SES: {}", ex.getMessage());
            reschedule(ids);
            return 0;
        }
        if (!response.isOk()) {
            log.error("SES.HOSPEDAJES rechazó la consulta de lotes: {} {}", response.code(), response.description());
            reschedule(ids);
            return 0;
        }
        Map<String, BatchResult> results = response.batches().stream()
                .filter(b -> b.batchId() != null)
                .collect(Collectors.toMap(BatchResult::batchId, Function.identity(), (a, b) -> b));
        int resolved = 0;
        for (UUID id : ids) {
            Boolean done = tx.execute(s -> apply(id, results, response.raw()));
            if (Boolean.TRUE.equals(done)) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean apply(UUID reportId, Map<String, BatchResult> results, String raw) {
        TravelerReport report = repository.findForUpdate(reportId).orElseThrow();
        boolean superseded = report.getStatus() == ReportStatus.CANCELLED && report.awaitsBatchResult();
        if (report.getStatus() != ReportStatus.SUBMITTED && !superseded) {
            return false;
        }
        Instant now = clock.instant();
        BatchResult batch = results.get(report.getSesBatchId());
        if (superseded && batch != null && batch.isFinished()) {
            CommunicationResult late = batch.communications().isEmpty() ? null : batch.communications().getFirst();
            report.recordLateResult(late == null ? null : late.communicationCode(),
                    late == null ? batch.stateDescription() : late.error(), raw);
            return true;
        }
        if (batch == null) {
            if (report.getAttempts() >= MAX_CHECKS) {
                report.markError("LOTE_NO_ENCONTRADO", "El lote " + report.getSesBatchId()
                        + " no aparece en las consultas del servicio", null, raw);
                return true;
            }
            report.scheduleNextCheck(now.plus(properties.batchCheckDelay()));
            return false;
        }
        if (!batch.isFinished()) {
            report.scheduleNextCheck(now.plus(properties.batchCheckDelay()));
            return false;
        }
        CommunicationResult result = batch.communications().isEmpty() ? null : batch.communications().getFirst();
        if (report.getOperation() == Operation.B) {
            // Anulación: el lote tramitado sin errores confirma que la comunicación original quedó anulada.
            if (batch.stateCode() == 1) {
                report.markAccepted(report.getCancelsReport().getSesCommunicationCode(), raw, now);
                report.getCancelsReport().markCancelled();
                return true;
            }
            report.markRejected(String.valueOf(batch.stateCode()), batch.stateDescription(), raw);
            return true;
        }
        if (result != null && result.communicationCode() != null) {
            report.markAccepted(result.communicationCode(), raw, now);
            log.info("Comunicación SES {} aceptada con código {}", reportId, result.communicationCode());
            return true;
        }
        String error = result != null && result.error() != null ? result.error() : batch.stateDescription();
        String type = result != null && result.errorType() != null ? result.errorType() : String.valueOf(batch.stateCode());
        report.markRejected(type, error, raw);
        log.error("SES.HOSPEDAJES rechazó la comunicación {}: {}", reportId, error);
        return true;
    }

    private void reschedule(List<UUID> ids) {
        Instant next = clock.instant().plus(properties.batchCheckDelay());
        tx.executeWithoutResult(s -> repository.findAllById(ids).forEach(r -> r.scheduleNextCheck(next)));
    }
}
