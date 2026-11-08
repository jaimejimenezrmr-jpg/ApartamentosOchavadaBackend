package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.availability.InventoryChangedEvent;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxService;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Traduce los eventos de dominio a eventos del outbox hacia Beds24, en la misma transacción que el cambio:
 * si el cambio se revierte, no se sincroniza nada; si se confirma, el outbox lo entregará con reintentos.
 * Solo se encolan tipologías vinculadas ({@code beds24RoomId}).
 */
@Component
public class Beds24SyncListener {

    public static final String INVENTORY_SYNC = "inventory.sync";
    public static final String BOOKING_PUSH = "booking.push";
    public static final String BOOKING_IMPORT = "booking.import";

    private final Beds24Properties properties;
    private final RoomTypeRepository roomTypeRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxService outboxService;

    public Beds24SyncListener(Beds24Properties properties, RoomTypeRepository roomTypeRepository,
                              ReservationRepository reservationRepository, OutboxService outboxService) {
        this.properties = properties;
        this.roomTypeRepository = roomTypeRepository;
        this.reservationRepository = reservationRepository;
        this.outboxService = outboxService;
    }

    @EventListener
    public void onInventoryChanged(InventoryChangedEvent event) {
        if (!properties.enabled()) {
            return;
        }
        for (UUID roomTypeId : event.roomTypeIds()) {
            enqueueInventory(roomTypeId, event.from(), event.to());
        }
    }

    @EventListener
    public void onReservationEvent(ReservationLifecycleEvent event) {
        if (!properties.enabled()) {
            return;
        }
        ReservationLifecycleEvent.Type type = event.type();
        if (type != ReservationLifecycleEvent.Type.CONFIRMED && type != ReservationLifecycleEvent.Type.STAY_CHANGED
                && type != ReservationLifecycleEvent.Type.CANCELLED) {
            return;
        }
        Reservation r = reservationRepository.findById(event.reservationId()).orElseThrow();
        Set<UUID> types = new LinkedHashSet<>();
        types.add(r.getRoomType().getId());
        if (r.getRoom() != null) {
            types.add(r.getRoom().getRoomType().getId());
        }
        for (UUID roomTypeId : types) {
            enqueueInventory(roomTypeId, r.getCheckInDate(), r.getCheckOutDate());
        }
        if (properties.pushBookings() && r.isLocallyOriginated() && r.getRoomType().getBeds24RoomId() != null) {
            outboxService.enqueue(OutboxDestination.BEDS24, "reservation", r.getId(), BOOKING_PUSH,
                    Map.of("reservationId", r.getId().toString()));
        }
    }

    /** Encola la sincronización del horizonte completo de todas las tipologías vinculadas. */
    public int enqueueFullSync() {
        int count = 0;
        for (RoomType roomType : roomTypeRepository.findByBeds24RoomIdIsNotNull()) {
            enqueueInventory(roomType.getId(), null, null);
            count++;
        }
        return count;
    }

    public void enqueueImport(long beds24BookingId, UUID webhookEventId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", beds24BookingId);
        if (webhookEventId != null) {
            payload.put("webhookEventId", webhookEventId.toString());
        }
        outboxService.enqueue(OutboxDestination.BEDS24, "beds24_booking", beds24BookingId, BOOKING_IMPORT, payload);
    }

    private void enqueueInventory(UUID roomTypeId, LocalDate from, LocalDate to) {
        boolean mapped = roomTypeRepository.findById(roomTypeId).map(rt -> rt.getBeds24RoomId() != null).orElse(false);
        if (!mapped) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomTypeId", roomTypeId.toString());
        if (from != null) {
            payload.put("from", from.toString());
        }
        if (to != null) {
            payload.put("to", to.toString());
        }
        outboxService.enqueue(OutboxDestination.BEDS24, "room_type", roomTypeId, INVENTORY_SYNC, payload);
    }
}
