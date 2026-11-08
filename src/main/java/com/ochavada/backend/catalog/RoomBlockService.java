package com.ochavada.backend.catalog;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.catalog.dto.RoomBlockRequest;
import com.ochavada.backend.catalog.dto.RoomBlockResponse;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.security.CurrentUser;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloqueos de unidades (mantenimiento, uso propio...). Un bloqueo nunca puede pisar una reserva viva.
 */
@Service
public class RoomBlockService {

    static final int MAX_BLOCK_DAYS = 366;
    static final int MAX_QUERY_DAYS = 400;

    private final RoomBlockRepository repository;
    private final RoomRepository roomRepository;
    private final InventoryRepository inventoryRepository;
    private final PropertyCalendar calendar;
    private final ApplicationEventPublisher events;

    public RoomBlockService(RoomBlockRepository repository, RoomRepository roomRepository,
                            InventoryRepository inventoryRepository, PropertyCalendar calendar,
                            ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.inventoryRepository = inventoryRepository;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public List<RoomBlockResponse> list(LocalDate from, LocalDate to, UUID roomId) {
        if (!to.isAfter(from)) {
            throw new BadRequestException("validation.date_range", "La fecha 'to' debe ser posterior a 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_QUERY_DAYS) {
            throw new BadRequestException("validation.date_range",
                    "El rango consultado no puede superar " + MAX_QUERY_DAYS + " días");
        }
        return repository.findOverlapping(from, to, roomId).stream().map(RoomBlockResponse::from).toList();
    }

    @Transactional
    public RoomBlockResponse create(RoomBlockRequest request) {
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("unidad", request.roomId()));
        if (ChronoUnit.DAYS.between(request.startDate(), request.endDate()) > MAX_BLOCK_DAYS) {
            throw new BadRequestException("room_block.too_long",
                    "Un bloqueo no puede superar " + MAX_BLOCK_DAYS + " días");
        }
        if (!request.endDate().isAfter(calendar.today())) {
            throw new BadRequestException("room_block.in_past", "No se pueden crear bloqueos en el pasado");
        }
        if (repository.existsOverlapping(room.getId(), request.startDate(), request.endDate())) {
            throw new ConflictException("room_block.overlap",
                    "La unidad " + room.getNumber() + " ya tiene un bloqueo en esas fechas");
        }
        if (inventoryRepository.roomHasActiveReservation(room.getId(), request.startDate(), request.endDate())) {
            throw new ConflictException("room_block.reservation_conflict",
                    "La unidad " + room.getNumber() + " tiene reservas en esas fechas; reubíquelas antes de bloquearla");
        }
        RoomBlock block = new RoomBlock(room, request.startDate(), request.endDate(), request.blockType(),
                request.reason() == null || request.reason().isBlank() ? null : request.reason().trim(),
                CurrentUser.staffUserId().orElse(null));
        RoomBlockResponse response = RoomBlockResponse.from(repository.saveAndFlush(block));
        events.publishEvent(InventoryChangedEvent.nights(room.getRoomType().getId(), block.getStartDate(), block.getEndDate()));
        return response;
    }

    @Transactional
    public void delete(UUID id) {
        RoomBlock block = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("bloqueo", id));
        InventoryChangedEvent changed = InventoryChangedEvent.nights(block.getRoom().getRoomType().getId(),
                block.getStartDate(), block.getEndDate());
        repository.delete(block);
        repository.flush();
        events.publishEvent(changed);
    }
}
