package com.ochavada.backend.housekeeping;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.catalog.dto.RoomResponse;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.housekeeping.checklist.ChecklistService;
import com.ochavada.backend.housekeeping.dto.RoomBoardItem;
import com.ochavada.backend.housekeeping.dto.TaskDetailResponse;
import com.ochavada.backend.housekeeping.dto.TaskDetailResponse.CheckResponse;
import com.ochavada.backend.housekeeping.dto.TaskRequest;
import com.ochavada.backend.housekeeping.dto.TaskResponse;
import com.ochavada.backend.incident.IncidentService;
import com.ochavada.backend.lostitem.LostItemService;
import com.ochavada.backend.media.MediaFile;
import com.ochavada.backend.media.MediaOwnerType;
import com.ochavada.backend.media.MediaResponse;
import com.ochavada.backend.media.MediaService;
import com.ochavada.backend.security.CurrentUser;
import com.ochavada.backend.security.SecurityRoles;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserRepository;
import com.ochavada.backend.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Gobernanza: tareas de limpieza y estado de las unidades.
 * <p>
 * Ciclo de la unidad: salida → PENDIENTE DE LIMPIEZA ({@code DIRTY}) → EN LIMPIEZA ({@code IN_PROGRESS}) →
 * LIMPIO pendiente de inspección ({@code CLEAN}) → LISTO ({@code INSPECTED}). Nunca de limpio directamente al
 * huésped: solo una inspección deja la unidad lista, y debe hacerla una persona distinta de quien limpió (salvo
 * administración). El personal de limpieza solo actúa sobre tareas sin asignar o asignadas a sí mismo.
 */
@Service
public class HousekeepingService {

    private static final List<TaskStatus> OPEN = List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);
    private static final int CHECKOUT_PRIORITY = 2;
    /** Estados que solo recepción o administración pueden fijar a mano: dan la unidad por lista o la retiran. */
    private static final Set<HousekeepingStatus> RECEPTION_ONLY_STATUSES =
            EnumSet.of(HousekeepingStatus.INSPECTED, HousekeepingStatus.OUT_OF_SERVICE);

    private final HousekeepingTaskRepository repository;
    private final RoomRepository roomRepository;
    private final AppUserRepository userRepository;
    private final ChecklistService checklistService;
    private final MediaService mediaService;
    private final IncidentService incidentService;
    private final LostItemService lostItemService;
    private final AuditService auditService;
    private final PropertyCalendar calendar;
    private final PropertyProperties property;
    private final JdbcClient jdbc;
    private final Clock clock;

    public HousekeepingService(HousekeepingTaskRepository repository, RoomRepository roomRepository,
                               AppUserRepository userRepository, ChecklistService checklistService,
                               MediaService mediaService, IncidentService incidentService,
                               LostItemService lostItemService, AuditService auditService, PropertyCalendar calendar,
                               PropertyProperties property, JdbcClient jdbc, Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.checklistService = checklistService;
        this.mediaService = mediaService;
        this.incidentService = incidentService;
        this.lostItemService = lostItemService;
        this.auditService = auditService;
        this.calendar = calendar;
        this.property = property;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public List<TaskResponse> board(LocalDate date, TaskStatus status, UUID assignedUserId) {
        List<HousekeepingTask> tasks = repository.findBoard(date == null ? calendar.today() : date, status, assignedUserId);
        Map<UUID, Long> photoCounts = photoCounts(tasks.stream().flatMap(t -> t.getChecks().stream()).toList());
        Map<UUID, String> names = names(tasks.stream().map(HousekeepingTask::getAssignedUserId).toList());
        return tasks.stream().map(t -> TaskResponse.from(t, photoCounts, names)).toList();
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse detail(UUID taskId) {
        HousekeepingTask task = find(taskId);
        Map<UUID, List<MediaResponse>> photos = new HashMap<>();
        for (MediaFile m : mediaService.list(MediaOwnerType.TASK_CHECK,
                task.getChecks().stream().map(HousekeepingTaskCheck::getId).toList())) {
            photos.computeIfAbsent(m.getOwnerId(), k -> new ArrayList<>()).add(MediaResponse.from(m));
        }
        Map<UUID, Long> counts = photos.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size()));
        List<CheckResponse> checks = task.getChecks().stream()
                .map(c -> CheckResponse.from(c, photos.getOrDefault(c.getId(), List.of())))
                .toList();
        return new TaskDetailResponse(TaskResponse.from(task, counts, names(Collections.singletonList(task.getAssignedUserId()))),
                checks, incidentService.forTask(taskId), lostItemService.forTask(taskId));
    }

    /** Todas las unidades no dadas de baja, con su estado de limpieza, ocupación y movimientos de hoy. */
    @Transactional(readOnly = true)
    public List<RoomBoardItem> rooms() {
        return jdbc.sql("""
                        SELECT r.id, r.number, r.name, rt.name AS type_name, r.floor, r.status, r.housekeeping_status,
                               r.housekeeping_updated_at,
                               EXISTS (SELECT 1 FROM reservation x WHERE x.room_id = r.id AND x.status = 'CHECKED_IN') AS occupied,
                               EXISTS (SELECT 1 FROM reservation x WHERE x.room_id = r.id AND x.status = 'CHECKED_IN'
                                          AND x.check_out_date = :today) AS departure_today,
                               EXISTS (SELECT 1 FROM reservation x WHERE x.room_id = r.id AND x.status = 'CONFIRMED'
                                          AND x.check_in_date = :today) AS arrival_today,
                               (SELECT count(*) FROM incident i WHERE i.room_id = r.id
                                   AND i.status IN ('OPEN', 'IN_PROGRESS')) AS open_incidents
                          FROM room r JOIN room_type rt ON rt.id = r.room_type_id
                         WHERE r.status <> 'INACTIVE'
                         ORDER BY r.number
                        """)
                .param("today", calendar.today())
                .query((rs, n) -> new RoomBoardItem(
                        rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"),
                        rs.getString("type_name"), rs.getObject("floor", Integer.class),
                        RoomStatus.valueOf(rs.getString("status")),
                        HousekeepingStatus.valueOf(rs.getString("housekeeping_status")),
                        rs.getTimestamp("housekeeping_updated_at") == null ? null
                                : rs.getTimestamp("housekeeping_updated_at").toInstant(),
                        rs.getBoolean("occupied"), rs.getBoolean("departure_today"), rs.getBoolean("arrival_today"),
                        rs.getLong("open_incidents")))
                .list();
    }

    // ------------------------------------------------------------------ tareas

    @Transactional
    public TaskResponse create(TaskRequest request) {
        Room room = findRoom(request.roomId());
        if (request.assignedUserId() != null) {
            requireAssignableUser(request.assignedUserId());
        }
        HousekeepingTask task = new HousekeepingTask(room, request.reservationId(), request.taskType(),
                request.priority() == null ? 3 : request.priority(), request.scheduledDate(), request.dueBy(),
                blankToNull(request.notes()), CurrentUser.staffUserId().orElse(null));
        if (request.assignedUserId() != null) {
            task.assignTo(request.assignedUserId());
        }
        repository.saveAndFlush(task);
        auditService.record("housekeeping.task_created", "housekeeping_task", task.getId(),
                Map.of("room", room.getNumber(), "type", task.getTaskType().name()));
        return response(task);
    }

    @Transactional
    public TaskResponse assign(UUID taskId, UUID userId) {
        HousekeepingTask task = find(taskId);
        requireAssignableUser(userId);
        task.assignTo(userId);
        repository.flush();
        return response(task);
    }

    @Transactional
    public TaskResponse start(UUID taskId) {
        HousekeepingTask task = find(taskId);
        UUID userId = requireActor(task);
        Instant now = clock.instant();
        task.start(userId, now, checklistService.resolveFor(task.getRoom().getRoomType().getId()).orElse(null));
        task.getRoom().changeHousekeepingStatus(HousekeepingStatus.IN_PROGRESS, now);
        repository.flush();
        auditService.record("housekeeping.task_started", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber(), "checks", task.getChecks().size()));
        return response(task);
    }

    @Transactional
    public CheckResponse markCheck(UUID taskId, UUID checkId, boolean checked, String comment) {
        HousekeepingTask task = find(taskId);
        UUID userId = requireActor(task);
        HousekeepingTaskCheck check = findCheck(task, checkId);
        task.markCheck(check, checked, blankToNull(comment), userId, clock.instant());
        repository.flush();
        return CheckResponse.from(check, photosOf(check));
    }

    @Transactional
    public MediaResponse addCheckPhoto(UUID taskId, UUID checkId, MultipartFile file) {
        HousekeepingTask task = find(taskId);
        requireActor(task);
        HousekeepingTaskCheck check = findCheck(task, checkId);
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ConflictException("housekeeping.task_not_started",
                    "Las fotos se añaden con la tarea en curso");
        }
        return MediaResponse.from(mediaService.store(MediaOwnerType.TASK_CHECK, check.getId(), file));
    }

    @Transactional
    public void deleteCheckPhoto(UUID taskId, UUID checkId, UUID mediaId) {
        HousekeepingTask task = find(taskId);
        requireActor(task);
        HousekeepingTaskCheck check = findCheck(task, checkId);
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ConflictException("housekeeping.task_not_started",
                    "Las fotos solo se pueden quitar con la tarea en curso");
        }
        mediaService.delete(MediaOwnerType.TASK_CHECK, check.getId(), mediaId);
    }

    @Transactional
    public TaskResponse complete(UUID taskId, String notes) {
        HousekeepingTask task = find(taskId);
        UUID userId = requireActor(task);
        Instant now = clock.instant();
        Map<UUID, Long> photoCounts = photoCounts(task.getChecks());
        List<String> missing = new ArrayList<>();
        for (HousekeepingTaskCheck c : task.getChecks()) {
            if (!c.isChecked()) {
                missing.add(c.getLabel());
            } else if (c.isPhotoRequired() && photoCounts.getOrDefault(c.getId(), 0L) == 0) {
                missing.add(c.getLabel() + " (foto)");
            }
        }
        task.complete(userId, notes, missing, now);
        if (task.getTaskType().cleansRoom()) {
            task.getRoom().changeHousekeepingStatus(HousekeepingStatus.CLEAN, now);
        }
        repository.flush();
        auditService.record("housekeeping.task_completed", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber()));
        return response(task);
    }

    /** Inspección superada: la unidad queda LISTA para el huésped. */
    @Transactional
    public TaskResponse verify(UUID taskId) {
        HousekeepingTask task = find(taskId);
        UUID inspector = requireInspector(task);
        Instant now = clock.instant();
        task.verify(inspector, now);
        if (task.getTaskType().cleansRoom()) {
            task.getRoom().changeHousekeepingStatus(HousekeepingStatus.INSPECTED, now);
        }
        repository.flush();
        auditService.record("housekeeping.task_inspected", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber(), "result", "APPROVED"));
        return response(task);
    }

    /** Inspección no superada: la limpieza vuelve a pendiente con el motivo y la unidad, a pendiente de limpieza. */
    @Transactional
    public TaskResponse reject(UUID taskId, String reason) {
        HousekeepingTask task = find(taskId);
        UUID inspector = requireInspector(task);
        String cleanReason = blankToNull(reason);
        if (cleanReason == null) {
            throw new BadRequestException("housekeeping.rejection_reason_required", "Indique qué hay que repetir");
        }
        Instant now = clock.instant();
        task.reject(inspector, cleanReason, now);
        if (task.getTaskType().cleansRoom()) {
            task.getRoom().changeHousekeepingStatus(HousekeepingStatus.DIRTY, now);
        }
        repository.flush();
        auditService.record("housekeeping.task_inspected", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber(), "result", "REJECTED", "reason", cleanReason));
        return response(task);
    }

    @Transactional
    public TaskResponse cancel(UUID taskId) {
        HousekeepingTask task = find(taskId);
        task.cancel();
        repository.flush();
        auditService.record("housekeeping.task_cancelled", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber()));
        return response(task);
    }

    /**
     * Cambio manual del estado de limpieza de una unidad (p. ej. repaso sin tarea). Dar una unidad por LISTA o
     * ponerla fuera de servicio queda reservado a recepción y administración.
     */
    @Transactional
    public RoomResponse changeRoomStatus(UUID roomId, HousekeepingStatus status) {
        if (RECEPTION_ONLY_STATUSES.contains(status) && !CurrentUser.isReceptionOrAbove()) {
            throw new AccessDeniedException("Solo recepción o administración pueden marcar una unidad como "
                    + (status == HousekeepingStatus.INSPECTED ? "lista" : "fuera de servicio"));
        }
        Room room = findRoom(roomId);
        HousekeepingStatus from = room.getHousekeepingStatus();
        room.changeHousekeepingStatus(status, clock.instant());
        roomRepository.flush();
        auditService.record("room.housekeeping_status", "room", roomId,
                Map.of("room", room.getNumber(), "from", from.name(), "status", status.name()));
        return RoomResponse.from(room);
    }

    /**
     * Tras una salida: la unidad queda pendiente de limpieza y se programa su limpieza para hoy (una sola vez).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleCheckoutCleaning(Room room, UUID reservationId) {
        Instant now = clock.instant();
        room.changeHousekeepingStatus(HousekeepingStatus.DIRTY, now);
        LocalDate today = calendar.today();
        if (repository.existsByRoomIdAndTaskTypeAndScheduledDateAndStatusIn(room.getId(), TaskType.CHECKOUT_CLEAN,
                today, OPEN)) {
            return;
        }
        // Debe estar lista antes de la hora de entrada del siguiente huésped.
        Instant dueBy = today.atTime(property.checkInTime()).atZone(property.timeZone()).toInstant();
        repository.save(new HousekeepingTask(room, reservationId, TaskType.CHECKOUT_CLEAN, CHECKOUT_PRIORITY, today,
                dueBy.isAfter(now) ? dueBy : null, null, CurrentUser.staffUserId().orElse(null)));
    }

    // ------------------------------------------------------------------ permisos y utilidades

    private UUID requireActor(HousekeepingTask task) {
        UUID userId = CurrentUser.staffUserId()
                .orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        if (!CurrentUser.isReceptionOrAbove() && task.getAssignedUserId() != null
                && !task.getAssignedUserId().equals(userId)) {
            throw new AccessDeniedException("La tarea está asignada a otra persona");
        }
        return userId;
    }

    /** Cualquier miembro del personal puede inspeccionar, pero no su propia limpieza (salvo administración). */
    private static UUID requireInspector(HousekeepingTask task) {
        UUID userId = CurrentUser.staffUserId()
                .orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        if (Objects.equals(userId, task.getCompletedByUserId()) && !CurrentUser.hasAnyRole(SecurityRoles.ADMIN)) {
            throw new AccessDeniedException("La inspección debe hacerla una persona distinta de quien limpió");
        }
        return userId;
    }

    private void requireAssignableUser(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", userId));
        if (!user.isEnabled() || (user.getRole() != UserRole.HOUSEKEEPING && user.getRole() != UserRole.RECEPTIONIST)) {
            throw new BadRequestException("housekeeping.invalid_assignee",
                    "Solo se pueden asignar tareas a personal de limpieza o recepción activo");
        }
    }

    /** Respuesta a una acción sobre una tarea, con el recuento de fotos y el nombre de la persona asignada. */
    private TaskResponse response(HousekeepingTask task) {
        return TaskResponse.from(task, photoCounts(task.getChecks()),
                names(Collections.singletonList(task.getAssignedUserId())));
    }

    private Map<UUID, Long> photoCounts(List<HousekeepingTaskCheck> checks) {
        List<UUID> ids = checks.stream().map(HousekeepingTaskCheck::getId).toList();
        return mediaService.list(MediaOwnerType.TASK_CHECK, ids).stream()
                .collect(Collectors.groupingBy(MediaFile::getOwnerId, Collectors.counting()));
    }

    private List<MediaResponse> photosOf(HousekeepingTaskCheck check) {
        return mediaService.list(MediaOwnerType.TASK_CHECK, check.getId()).stream().map(MediaResponse::from).toList();
    }

    private Map<UUID, String> names(List<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    private static HousekeepingTaskCheck findCheck(HousekeepingTask task, UUID checkId) {
        return task.findCheck(checkId).orElseThrow(() -> new ResourceNotFoundException("punto de la lista", checkId));
    }

    private HousekeepingTask find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("tarea", id));
    }

    private Room findRoom(UUID id) {
        return roomRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("unidad", id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
