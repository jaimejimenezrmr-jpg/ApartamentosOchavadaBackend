package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.channel.SyncStateRepository;
import com.ochavada.backend.channel.beds24.Beds24BookingImporter.ImportResult;
import com.ochavada.backend.channel.beds24.Beds24Dtos.Property;
import com.ochavada.backend.channel.beds24.Beds24PollService.PollSummary;
import com.ochavada.backend.common.error.ServiceUnavailableException;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxEvent.OutboxStatus;
import com.ochavada.backend.integration.outbox.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Operaciones de administración de la conexión con Beds24. */
@Service
public class Beds24AdminService {

    private final Beds24Properties properties;
    private final Beds24TokenManager tokenManager;
    private final Beds24Gateway gateway;
    private final Beds24SyncListener syncListener;
    private final Beds24PollService pollService;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final OutboxRepository outboxRepository;
    private final SyncStateRepository syncState;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public Beds24AdminService(Beds24Properties properties, Beds24TokenManager tokenManager, Beds24Gateway gateway,
                              Beds24SyncListener syncListener, Beds24PollService pollService,
                              RoomTypeRepository roomTypeRepository, RoomRepository roomRepository,
                              OutboxRepository outboxRepository, SyncStateRepository syncState,
                              AuditService auditService, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.gateway = gateway;
        this.syncListener = syncListener;
        this.pollService = pollService;
        this.roomTypeRepository = roomTypeRepository;
        this.roomRepository = roomRepository;
        this.outboxRepository = outboxRepository;
        this.syncState = syncState;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Status status() {
        return tx.execute(s -> {
            List<RoomType> types = roomTypeRepository.findAllByOrderByDisplayOrderAscNameAsc();
            List<MappedRoomType> mapping = types.stream().map(rt -> {
                List<Room> rooms = roomRepository.findByRoomTypeIdOrderByNumberAsc(rt.getId());
                return new MappedRoomType(rt.getId(), rt.getCode(), rt.getName(), rt.isActive(), rt.getBeds24RoomId(),
                        rooms.size(), (int) rooms.stream().filter(r -> r.getBeds24UnitId() != null).count());
            }).toList();
            return new Status(properties.enabled(), tokenManager.isConnected(), properties.pushBookings(),
                    properties.syncHorizonDays(), syncState.lastSyncedAt(Beds24PollService.MODIFIED_MARK).orElse(null),
                    outboxRepository.countByDestinationAndStatus(OutboxDestination.BEDS24, OutboxStatus.PENDING),
                    outboxRepository.countByDestinationAndStatus(OutboxDestination.BEDS24, OutboxStatus.FAILED),
                    outboxRepository.countByDestinationAndStatus(OutboxDestination.BEDS24, OutboxStatus.DEAD),
                    mapping);
        });
    }

    /** Canjea el código de invitación generado en Beds24 (Ajustes → Marketplace → API). */
    public Status connect(String inviteCode) {
        requireEnabled();
        tokenManager.setup(inviteCode);
        tx.executeWithoutResult(s -> {
            auditService.record("channel.beds24_connected", "integration", "BEDS24", Map.of());
            syncListener.enqueueFullSync();
        });
        return status();
    }

    /** Habitaciones y unidades de Beds24 junto a su vinculación con el catálogo (ayuda al mapeo). */
    public List<RemoteRoom> remoteRooms() {
        requireConnected();
        List<Property> properties = gateway.properties();
        return tx.execute(s -> {
            Map<Long, RoomType> byBeds24Id = roomTypeRepository.findByBeds24RoomIdIsNotNull().stream()
                    .collect(Collectors.toMap(RoomType::getBeds24RoomId, Function.identity()));
            List<RemoteRoom> result = new ArrayList<>();
            for (Property property : properties) {
                if (property.roomTypes() == null) {
                    continue;
                }
                for (Beds24Dtos.Room room : property.roomTypes()) {
                    RoomType mapped = byBeds24Id.get(room.id());
                    Map<Integer, String> mappedUnits = mapped == null ? Map.of()
                            : roomRepository.findByRoomTypeIdOrderByNumberAsc(mapped.getId()).stream()
                                    .filter(r -> r.getBeds24UnitId() != null)
                                    .collect(Collectors.toMap(Room::getBeds24UnitId, Room::getNumber, (a, b) -> a));
                    List<RemoteUnit> units = room.units() == null ? List.of() : room.units().stream()
                            .filter(u -> u.id() != null)
                            .map(u -> new RemoteUnit(u.id(), u.name(), mappedUnits.get(u.id().intValue())))
                            .toList();
                    result.add(new RemoteRoom(property.id(), property.name(), room.id(), room.name(), room.qty(),
                            room.maxPeople(), mapped == null ? null : mapped.getId(),
                            mapped == null ? null : mapped.getCode(), units));
                }
            }
            return result;
        });
    }

    public int fullSync() {
        requireConnected();
        Integer count = tx.execute(s -> {
            int enqueued = syncListener.enqueueFullSync();
            auditService.record("channel.beds24_full_sync", "integration", "BEDS24", Map.of("roomTypes", enqueued));
            return enqueued;
        });
        return Objects.requireNonNull(count);
    }

    public PollSummary poll() {
        requireConnected();
        return pollService.pollModified();
    }

    public PollSummary importUpcoming() {
        requireConnected();
        PollSummary summary = pollService.importUpcoming();
        tx.executeWithoutResult(s -> auditService.record("channel.beds24_initial_import", "integration", "BEDS24",
                Map.of("total", summary.total(), "deferred", summary.deferred())));
        return summary;
    }

    public ImportResult importBooking(long bookingId) {
        requireConnected();
        return pollService.importOne(bookingId);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ServiceUnavailableException("beds24.disabled", "Beds24 desactivado",
                    "Active la integración con BEDS24_ENABLED=true");
        }
    }

    private void requireConnected() {
        requireEnabled();
        if (!tokenManager.isConnected()) {
            throw new ServiceUnavailableException("beds24.not_connected", "Beds24 no conectado",
                    "Conecte Beds24 con un código de invitación o configure BEDS24_REFRESH_TOKEN");
        }
    }

    public record Status(boolean enabled, boolean connected, boolean pushBookings, int syncHorizonDays,
                         Instant lastBookingPollAt, long pendingEvents, long failedEvents, long deadEvents,
                         List<MappedRoomType> roomTypes) {
    }

    public record MappedRoomType(UUID roomTypeId, String code, String name, boolean active, Long beds24RoomId,
                                 int units, int unitsMapped) {
    }

    public record RemoteRoom(Long propertyId, String propertyName, Long beds24RoomId, String name, Integer qty,
                             Integer maxPeople, UUID mappedRoomTypeId, String mappedRoomTypeCode,
                             List<RemoteUnit> units) {
    }

    public record RemoteUnit(Long beds24UnitId, String name, String mappedRoomNumber) {
    }
}
