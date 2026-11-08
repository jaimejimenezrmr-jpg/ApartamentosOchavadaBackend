package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ServiceUnavailableException;
import com.ochavada.backend.common.error.UnauthorizedException;
import com.ochavada.backend.integration.webhook.InboundWebhookEvent;
import com.ochavada.backend.integration.webhook.InboundWebhookEvent.WebhookProvider;
import com.ochavada.backend.integration.webhook.InboundWebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook de reservas de Beds24. Beds24 no firma sus webhooks: se autentica con un secreto que se configura
 * como cabecera personalizada del webhook. El cuerpo solo se usa para saber qué reserva ha cambiado
 * (fetch-on-notify): su estado real se lee después de la API, de modo que una notificación falsa o
 * desordenada no puede introducir datos. La importación se hace de forma asíncrona desde el outbox para
 * responder enseguida y reintentar si Beds24 o la base de datos fallan.
 * <p>
 * Del cuerpo no se guardan datos personales: solo el identificador de la reserva.
 */
@Slf4j
@Service
public class Beds24WebhookService {

    public static final String TOKEN_HEADER = "X-Ochavada-Webhook-Token";
    private static final List<String> ID_PATHS = List.of("/booking/id", "/id", "/bookId", "/bookid", "/booking/bookId");

    private final Beds24Properties properties;
    private final InboundWebhookEventRepository repository;
    private final Beds24SyncListener syncListener;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public Beds24WebhookService(Beds24Properties properties, InboundWebhookEventRepository repository,
                                Beds24SyncListener syncListener, JsonMapper jsonMapper,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.syncListener = syncListener;
        this.jsonMapper = jsonMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param body          cuerpo recibido (JSON) o {@code null}
     * @param queryBookId   identificador recibido como parámetro (webhooks antiguos por GET)
     * @return reservas notificadas (normalmente una)
     */
    public Set<Long> receive(String body, String queryBookId, String token) {
        if (!properties.enabled() || properties.webhookToken() == null || properties.webhookToken().isBlank()) {
            throw new ServiceUnavailableException("beds24.webhook_disabled", "Webhook de Beds24 no disponible",
                    "La integración con Beds24 no está activada o no tiene secreto de webhook");
        }
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                properties.webhookToken().getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("beds24.webhook_unauthorized", "Secreto del webhook de Beds24 no válido");
        }
        Set<Long> bookingIds = extractBookingIds(body, queryBookId);
        if (bookingIds.isEmpty()) {
            throw new BadRequestException("beds24.webhook_without_booking", "La notificación no identifica ninguna reserva");
        }
        String digest = sha256(body == null ? "" : body).substring(0, 16);
        for (Long bookingId : bookingIds) {
            register(bookingId, digest);
        }
        return bookingIds;
    }

    /**
     * Cada notificación genera una importación: Beds24 puede enviar el mismo cuerpo para cambios distintos
     * y la importación lee siempre el estado actual, así que repetirla es inocuo. El identificador incluye
     * el instante de recepción y la huella del cuerpo (solo deduplica reenvíos idénticos simultáneos).
     */
    private void register(long bookingId, String digest) {
        String eventId = "booking:" + bookingId + ":" + clock.millis() + ":" + digest;
        try {
            tx.executeWithoutResult(s -> {
                InboundWebhookEvent event = repository.saveAndFlush(new InboundWebhookEvent(WebhookProvider.BEDS24,
                        eventId, "booking.modified", jsonMapper.writeValueAsString(Map.of("bookingId", bookingId)),
                        clock.instant()));
                syncListener.enqueueImport(bookingId, event.getId());
            });
        } catch (DataIntegrityViolationException ex) {
            if (!String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uq_inbound_webhook_event")) {
                throw ex;
            }
            log.debug("Webhook de Beds24 duplicado y simultáneo para la reserva {}", bookingId);
        }
    }

    Set<Long> extractBookingIds(String body, String queryBookId) {
        Set<Long> ids = new LinkedHashSet<>();
        parseId(queryBookId).ifPresent(ids::add);
        if (body == null || body.isBlank()) {
            return ids;
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new BadRequestException("beds24.webhook_malformed", "El cuerpo del webhook no es JSON válido");
        }
        if (root.isArray()) {
            root.forEach(item -> idOf(item).ifPresent(ids::add));
        } else {
            idOf(root).ifPresent(ids::add);
        }
        return ids;
    }

    private static Optional<Long> idOf(JsonNode node) {
        for (String path : ID_PATHS) {
            JsonNode value = node.at(path);
            if (value.isNumber()) {
                return Optional.of(value.asLong()).filter(id -> id > 0);
            }
            if (value.isString()) {
                Optional<Long> parsed = parseId(value.asString());
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> parseId(String value) {
        if (value == null || !value.trim().matches("^\\d{1,18}$")) {
            return Optional.empty();
        }
        long id = Long.parseLong(value.trim());
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
