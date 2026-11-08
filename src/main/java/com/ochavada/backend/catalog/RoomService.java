package com.ochavada.backend.catalog;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.catalog.dto.RoomRequest;
import com.ochavada.backend.catalog.dto.RoomResponse;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomRepository repository;
    private final RoomTypeRepository roomTypeRepository;

    private final ApplicationEventPublisher events;

    public RoomService(RoomRepository repository, RoomTypeRepository roomTypeRepository,
                       ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.roomTypeRepository = roomTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> list(UUID roomTypeId) {
        List<Room> rooms = roomTypeId == null
                ? repository.findAllByOrderByNumberAsc()
                : repository.findByRoomTypeIdOrderByNumberAsc(roomTypeId);
        return rooms.stream().map(RoomResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID id) {
        return RoomResponse.from(find(id));
    }

    @Transactional
    public RoomResponse create(RoomRequest request) {
        if (repository.existsByNumber(request.number())) {
            throw duplicateNumber(request.number());
        }
        Room room = new Room(findRoomType(request.roomTypeId()), request.number());
        apply(room, request);
        RoomResponse response = RoomResponse.from(repository.saveAndFlush(room));
        events.publishEvent(InventoryChangedEvent.fullHorizon(room.getRoomType().getId()));
        return response;
    }

    @Transactional
    public RoomResponse update(UUID id, RoomRequest request) {
        Room room = find(id);
        room.assertVersion(request.version());
        if (repository.existsByNumberAndIdNot(request.number(), id)) {
            throw duplicateNumber(request.number());
        }
        UUID previousType = room.getRoomType().getId();
        room.setRoomType(findRoomType(request.roomTypeId()));
        room.setNumber(request.number());
        apply(room, request);
        RoomResponse response = RoomResponse.from(repository.saveAndFlush(room));
        events.publishEvent(InventoryChangedEvent.nights(List.of(previousType, room.getRoomType().getId()), null, null));
        return response;
    }

    /**
     * Elimina una unidad. Si tiene reservas, cerraduras o histórico la base de datos lo impide (409):
     * en ese caso debe marcarse como {@code INACTIVE}.
     */
    @Transactional
    public void delete(UUID id) {
        Room room = find(id);
        UUID roomTypeId = room.getRoomType().getId();
        repository.delete(room);
        repository.flush();
        events.publishEvent(InventoryChangedEvent.fullHorizon(roomTypeId));
    }

    Room find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("unidad", id));
    }

    private RoomType findRoomType(UUID id) {
        return roomTypeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("tipología", id));
    }

    private static void apply(Room room, RoomRequest request) {
        room.setName(request.name() == null || request.name().isBlank() ? null : request.name().trim());
        room.setFloor(request.floor());
        room.setStatus(request.status());
        room.setBeds24UnitId(request.beds24UnitId());
        room.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
    }

    private static ConflictException duplicateNumber(String number) {
        return new ConflictException("room.duplicate_number", "Ya existe una unidad con el número " + number);
    }
}
