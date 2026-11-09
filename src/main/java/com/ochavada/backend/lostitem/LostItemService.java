package com.ochavada.backend.lostitem;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.housekeeping.HousekeepingTask;
import com.ochavada.backend.housekeeping.HousekeepingTaskRepository;
import com.ochavada.backend.lostitem.LostItemDtos.CreateRequest;
import com.ochavada.backend.lostitem.LostItemDtos.LostItemResponse;
import com.ochavada.backend.lostitem.LostItemDtos.StatusRequest;
import com.ochavada.backend.lostitem.LostItemDtos.UpdateRequest;
import com.ochavada.backend.media.MediaFile;
import com.ochavada.backend.media.MediaOwnerType;
import com.ochavada.backend.media.MediaResponse;
import com.ochavada.backend.media.MediaService;
import com.ochavada.backend.operations.OpsCodeGenerator;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.security.CurrentUser;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Objetos olvidados. Limpieza los registra (con foto) y quedan vinculados a la reserva del huésped que se
 * alojó en la unidad; recepción le avisa y cierra el expediente (enviado, recogido o desechado).
 */
@Service
public class LostItemService {

    /** Días hacia atrás en los que se busca la estancia a la que atribuir un objeto encontrado sin tarea. */
    private static final int LOOKBACK_DAYS = 3;

    private final LostItemRepository repository;
    private final RoomRepository roomRepository;
    private final HousekeepingTaskRepository taskRepository;
    private final ReservationRepository reservationRepository;
    private final AppUserRepository userRepository;
    private final MediaService mediaService;
    private final OpsCodeGenerator codes;
    private final AuditService auditService;
    private final PropertyCalendar calendar;
    private final JdbcClient jdbc;
    private final Clock clock;

    public LostItemService(LostItemRepository repository, RoomRepository roomRepository,
                           HousekeepingTaskRepository taskRepository, ReservationRepository reservationRepository,
                           AppUserRepository userRepository, MediaService mediaService, OpsCodeGenerator codes,
                           AuditService auditService, PropertyCalendar calendar, JdbcClient jdbc, Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.taskRepository = taskRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
        this.codes = codes;
        this.auditService = auditService;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LostItemResponse> search(boolean openOnly) {
        return toResponses(repository.search(openOnly));
    }

    @Transactional(readOnly = true)
    public List<LostItemResponse> forTask(UUID taskId) {
        return toResponses(repository.findByTaskIdOrderByFoundAtAsc(taskId));
    }

    @Transactional(readOnly = true)
    public LostItemResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
    }

    @Transactional
    public LostItemResponse create(CreateRequest request) {
        UUID userId = CurrentUser.staffUserId().orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        Room room;
        UUID taskId = null;
        Reservation reservation = null;
        if (request.taskId() != null) {
            HousekeepingTask task = taskRepository.findById(request.taskId())
                    .orElseThrow(() -> new ResourceNotFoundException("tarea", request.taskId()));
            room = task.getRoom();
            taskId = task.getId();
            if (request.roomId() != null && !request.roomId().equals(room.getId())) {
                throw new BadRequestException("lost_item.room_mismatch", "La unidad no coincide con la de la tarea");
            }
            // La limpieza de salida conoce la reserva que acaba de irse.
            if (task.getReservationId() != null) {
                reservation = reservationRepository.findById(task.getReservationId()).orElse(null);
            }
        } else if (request.roomId() != null) {
            room = roomRepository.findById(request.roomId())
                    .orElseThrow(() -> new ResourceNotFoundException("unidad", request.roomId()));
        } else {
            throw new BadRequestException("lost_item.room_required", "Indique la unidad en la que se encontró");
        }
        if (reservation == null) {
            reservation = lastStay(room.getId()).flatMap(reservationRepository::findById).orElse(null);
        }
        LostItem item = new LostItem(codes.next(OpsCodeGenerator.Kind.OBJ), room, reservation, taskId,
                request.description().trim(), blankToNull(request.storageLocation()), userId, clock.instant());
        repository.saveAndFlush(item);
        auditService.record("lost_item.created", "lost_item", item.getId(), Map.of(
                "code", item.getCode(), "room", room.getNumber(),
                "reservation", reservation == null ? "" : reservation.getCode()));
        return toResponses(List.of(item)).getFirst();
    }

    @Transactional
    public LostItemResponse update(UUID id, UpdateRequest request) {
        LostItem item = find(id);
        item.assertVersion(request.version());
        item.update(blankToNull(request.storageLocation()), blankToNull(request.notes()));
        repository.flush();
        return get(id);
    }

    @Transactional
    public LostItemResponse changeStatus(UUID id, StatusRequest request) {
        LostItem item = find(id);
        item.assertVersion(request.version());
        LostItemStatus from = item.getStatus();
        item.moveTo(request.status(), clock.instant());
        repository.flush();
        auditService.record("lost_item.status_changed", "lost_item", id,
                Map.of("code", item.getCode(), "from", from.name(), "to", item.getStatus().name()));
        return get(id);
    }

    @Transactional
    public MediaResponse addPhoto(UUID id, MultipartFile file) {
        LostItem item = find(id);
        requireFinderOrReception(item);
        if (!item.getStatus().isOpen()) {
            throw new ConflictException("lost_item.closed", "El expediente del objeto ya está cerrado");
        }
        return MediaResponse.from(mediaService.store(MediaOwnerType.LOST_ITEM, id, file));
    }

    @Transactional
    public void deletePhoto(UUID id, UUID mediaId) {
        requireFinderOrReception(find(id));
        mediaService.delete(MediaOwnerType.LOST_ITEM, id, mediaId);
    }

    /** Estancia en curso o terminada hace poco en la unidad: a quien probablemente pertenece el objeto. */
    private Optional<UUID> lastStay(UUID roomId) {
        return jdbc.sql("""
                        SELECT id FROM reservation
                         WHERE room_id = :room AND status IN ('CHECKED_IN', 'CHECKED_OUT') AND check_out_date >= :since
                         ORDER BY CASE status WHEN 'CHECKED_OUT' THEN 0 ELSE 1 END, check_out_date DESC
                         LIMIT 1
                        """)
                .param("room", roomId).param("since", calendar.today().minusDays(LOOKBACK_DAYS))
                .query(UUID.class).optional();
    }

    private void requireFinderOrReception(LostItem item) {
        if (!CurrentUser.isReceptionOrAbove()
                && !Objects.equals(CurrentUser.staffUserId().orElse(null), item.getFoundByUserId())) {
            throw new AccessDeniedException("Solo quien encontró el objeto o recepción pueden modificar sus fotos");
        }
    }

    private LostItem find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("objeto olvidado", id));
    }

    private List<LostItemResponse> toResponses(List<LostItem> items) {
        Set<UUID> ids = items.stream().map(LostItem::getId).collect(Collectors.toSet());
        Map<UUID, List<MediaResponse>> photos = new HashMap<>();
        for (MediaFile m : mediaService.list(MediaOwnerType.LOST_ITEM, ids)) {
            photos.computeIfAbsent(m.getOwnerId(), k -> new ArrayList<>()).add(MediaResponse.from(m));
        }
        Set<UUID> finders = items.stream().map(LostItem::getFoundByUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> names = finders.isEmpty() ? Map.of() : userRepository.findAllById(finders).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        return items.stream()
                .map(l -> LostItemResponse.from(l, names.get(l.getFoundByUserId()), photos.getOrDefault(l.getId(), List.of())))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
