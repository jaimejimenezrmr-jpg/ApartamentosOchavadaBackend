package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.beds24.Beds24BookingImporter.ImportResult;
import com.ochavada.backend.channel.beds24.Beds24Dtos.Booking;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxEvent;
import com.ochavada.backend.integration.outbox.OutboxHandler;
import com.ochavada.backend.integration.webhook.InboundWebhookEvent;
import com.ochavada.backend.integration.webhook.InboundWebhookEventRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega los eventos del outbox con destino Beds24: sincronización de inventario, envío de reservas
 * propias e importación de reservas notificadas por webhook. Si Beds24 falla (incluido el 429 por
 * límite de créditos), la excepción hace que el outbox reintente con espera exponencial.
 */
@Slf4j
@Component
public class Beds24OutboxHandler implements OutboxHandler {

    private final Beds24Properties properties;
    private final Beds24InventorySync inventorySync;
    private final Beds24BookingPusher bookingPusher;
    private final Beds24BookingImporter bookingImporter;
    private final Beds24Gateway gateway;
    private final InboundWebhookEventRepository webhookRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public Beds24OutboxHandler(Beds24Properties properties, Beds24InventorySync inventorySync,
                               Beds24BookingPusher bookingPusher, Beds24BookingImporter bookingImporter,
                               Beds24Gateway gateway, InboundWebhookEventRepository webhookRepository,
                               JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.inventorySync = inventorySync;
        this.bookingPusher = bookingPusher;
        this.bookingImporter = bookingImporter;
        this.gateway = gateway;
        this.webhookRepository = webhookRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public OutboxDestination destination() {
        return OutboxDestination.BEDS24;
    }

    @Override
    public void handle(OutboxEvent event) {
        if (!properties.enabled()) {
            log.info("Beds24 desactivado: se descarta el evento {} ({})", event.getId(), event.getEventType());
            return;
        }
        JsonNode payload = jsonMapper.readTree(event.getPayload());
        switch (event.getEventType()) {
            case Beds24SyncListener.INVENTORY_SYNC -> inventorySync.sync(
                    UUID.fromString(payload.get("roomTypeId").asString()), date(payload, "from"), date(payload, "to"));
            case Beds24SyncListener.BOOKING_PUSH -> bookingPusher.push(UUID.fromString(payload.get("reservationId").asString()));
            case Beds24SyncListener.BOOKING_IMPORT -> importBooking(payload.get("bookingId").asLong(),
                    payload.hasNonNull("webhookEventId") ? UUID.fromString(payload.get("webhookEventId").asString()) : null);
            default -> throw new IllegalStateException("Evento de Beds24 desconocido: " + event.getEventType());
        }
    }

    private void importBooking(long bookingId, UUID webhookEventId) {
        Optional<Booking> booking = gateway.booking(bookingId);
        ImportResult result = booking.map(bookingImporter::importBooking).orElse(ImportResult.IGNORED);
        if (booking.isEmpty()) {
            log.warn("Beds24 notificó la reserva {} pero no la devuelve", bookingId);
        }
        if (webhookEventId != null) {
            webhookRepository.findById(webhookEventId).ifPresent(e -> markWebhook(e, result));
        }
    }

    private void markWebhook(InboundWebhookEvent event, ImportResult result) {
        if (result == ImportResult.IGNORED || result == ImportResult.OWN_ECHO) {
            event.markIgnored(clock.instant());
        } else {
            event.markProcessed(clock.instant());
        }
    }

    private static LocalDate date(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? LocalDate.parse(payload.get(field).asString()) : null;
    }
}
