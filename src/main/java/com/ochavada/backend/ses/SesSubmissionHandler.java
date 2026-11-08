package com.ochavada.backend.ses;

import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxEvent;
import com.ochavada.backend.integration.outbox.OutboxHandler;
import com.ochavada.backend.ses.ReportEnums.Operation;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.client.SesResponse;
import com.ochavada.backend.ses.client.SesSoapClient;
import com.ochavada.backend.ses.client.SesXmlBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envía a SES.HOSPEDAJES las comunicaciones encoladas en el outbox (un lote por comunicación).
 * <ul>
 *     <li>Código 0: queda {@code SUBMITTED} con su lote; {@link SesBatchService} consultará el resultado.</li>
 *     <li>Error de red o "error no controlado" del servicio: excepción → el outbox reintenta.</li>
 *     <li>Error de cabecera/datos: {@code ERROR}, para revisión (reintentar no lo arreglaría).</li>
 * </ul>
 */
@Slf4j
@Component
public class SesSubmissionHandler implements OutboxHandler {

    /** Errores del servicio que merecen reintento automático. */
    private static final Set<Integer> TRANSIENT_CODES = Set.of(10999);

    private final TravelerReportRepository repository;
    private final SesXmlBuilder xmlBuilder;
    private final SesSoapClient client;
    private final SesProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SesSubmissionHandler(TravelerReportRepository repository, SesXmlBuilder xmlBuilder, SesSoapClient client,
                                SesProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.xmlBuilder = xmlBuilder;
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public OutboxDestination destination() {
        return OutboxDestination.SES_HOSPEDAJES;
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID reportId = UUID.fromString(jsonMapper.readTree(event.getPayload()).get("reportId").asString());
        TravelerReport report = repository.findForUpdate(reportId).orElse(null);
        if (report == null || report.getStatus() != ReportStatus.PENDING) {
            return;
        }
        if (!properties.isConfigured()) {
            report.noteDeferred("Envío a SES.HOSPEDAJES desactivado o sin credenciales: pendiente de enviar");
            log.warn("Comunicación SES {} pendiente: el servicio no está configurado", reportId);
            return;
        }

        String requestXml;
        String envelope;
        if (report.getOperation() == Operation.B) {
            TravelerReport target = report.getCancelsReport();
            if (target.getSesCommunicationCode() == null) {
                if (target.awaitsBatchResult()) {
                    // El alta original aún no tiene código: el outbox reintentará cuando su lote se procese.
                    throw new IllegalStateException("La comunicación a anular aún no tiene código asignado");
                }
                // El alta nunca llegó a registrarse (rechazada): no hay nada que anular.
                report.markCancelled();
                return;
            }
            requestXml = xmlBuilder.cancellation(List.of(target.getSesCommunicationCode()));
            envelope = xmlBuilder.envelope("B", null, xmlBuilder.pack(requestXml));
        } else {
            requestXml = xmlBuilder.registration(report);
            envelope = xmlBuilder.envelope("A", report.getCommunicationType().sesCode(), xmlBuilder.pack(requestXml));
        }

        SesResponse response = client.send(envelope);
        Instant now = clock.instant();
        if (response.isOk() && response.batchId() != null) {
            report.markSubmitted(response.batchId(), requestXml, response.raw(), now, now.plus(properties.batchCheckDelay()));
            log.info("Comunicación SES {} ({} {}) enviada, lote {}", reportId, report.getOperation(),
                    report.getCommunicationType().sesCode(), response.batchId());
            return;
        }
        if (TRANSIENT_CODES.contains(response.code())) {
            throw new IllegalStateException("SES.HOSPEDAJES devolvió un error no controlado: " + response.description());
        }
        report.markError(String.valueOf(response.code()), response.description(), requestXml, response.raw());
        log.error("SES.HOSPEDAJES rechazó la comunicación {}: {} {}", reportId, response.code(), response.description());
    }
}
