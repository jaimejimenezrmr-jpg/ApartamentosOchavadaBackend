package com.ochavada.backend.housekeeping;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.dto.RoomResponse;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.housekeeping.dto.TaskRequest;
import com.ochavada.backend.housekeeping.dto.TaskResponse;
import com.ochavada.backend.security.CurrentUser;
import com.ochavada.backend.security.SecurityRoles;
import com.ochavada.backend.user.AppUserRepository;
import com.ochavada.backend.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gobernanza: tareas de limpieza/mantenimiento y estado de limpieza de las unidades.
 * <p>
 * Al iniciar una tarea la unidad pasa a {@code IN_PROGRESS}; al completarla una limpieza, a
 * {@code CLEAN}; al verificarla recepción, a {@code INSPECTED}. El personal de limpieza solo puede
 * actuar sobre tareas sin asignar o asignadas a sí mismo.
 */
@Service
public class HousekeepingService {

    private static final List<TaskStatus> OPEN = List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);
    private static final int CHECKOUT_PRIORITY = 2;

    private final HousekeepingTaskRepository repository;
    private final RoomRepository roomRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final PropertyCalendar calendar;
    private final PropertyProperties property;
    private final Clock clock;

    public HousekeepingService(HousekeepingTaskRepository repository, RoomRepository roomRepository,
                               AppUserRepository userRepository, AuditService auditService,
                               PropertyCalendar calendar, PropertyProperties property, Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.calendar = calendar;
        this.property = property;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> board(LocalDate date, TaskStatus status, UUID assignedUserId) {
        return repository.findBoard(date == null ? calendar.today() : date, status, assignedUserId).stream()
                .map(TaskResponse::from)
                .toList();
    }

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
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse assign(UUID taskId, UUID userId) {
        HousekeepingTask task = find(taskId);
        requireAssignableUser(userId);
        task.assignTo(userId);
        repository.flush();
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse start(UUID taskId) {
        HousekeepingTask task = find(taskId);
        UUID userId = requireActor(task);
        Instant now = clock.instant();
        task.start(userId, now);
        task.getRoom().changeHousekeepingStatus(HousekeepingStatus.IN_PROGRESS, now);
        repository.flush();
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse complete(UUID taskId, String notes) {
        HousekeepingTask task = find(taskId);
        requireActor(task);
        Instant now = clock.instant();
        task.complete(notes, now);
        if (task.getTaskType().cleansRoom()) {
            task.getRoom().changeHousekeepingStatus(HousekeepingStatus.CLEAN, now);
        }
        repository.flush();
        auditService.record("housekeeping.task_completed", "housekeeping_task", task.getId(),
                Map.of("room", task.getRoom().getNumber()));
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse verify(UUID taskId) {
        HousekeepingTask task = find(taskId);
        Instant now = clock.instant();
        task.verify(CurrentUser.staffUserId().orElse(null), now);
        if (task.getTaskType().cleansRoom()) {
            task.getRoom().changeHousekeepingStatus(HousekeepingStatus.INSPECTED, now);
        }
        repository.flush();
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse cancel(UUID taskId) {
        HousekeepingTask task = find(taskId);
        task.cancel();
        repository.flush();
        return TaskResponse.from(task);
    }

    /** Cambio manual del estado de limpieza de una unidad (p. ej. repaso sin tarea). */
    @Transactional
    public RoomResponse changeRoomStatus(UUID roomId, HousekeepingStatus status) {
        Room room = findRoom(roomId);
        room.changeHousekeepingStatus(status, clock.instant());
        roomRepository.flush();
        auditService.record("room.housekeeping_status", "room", roomId, Map.of("status", status.name()));
        return RoomResponse.from(room);
    }

    /**
     * Tras una salida: la unidad queda sucia y se programa su limpieza para hoy (una sola vez).
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

    private UUID requireActor(HousekeepingTask task) {
        UUID userId = CurrentUser.staffUserId()
                .orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        boolean housekeepingOnly = hasOnlyHousekeepingRole();
        if (housekeepingOnly && task.getAssignedUserId() != null && !task.getAssignedUserId().equals(userId)) {
            throw new AccessDeniedException("La tarea está asignada a otra persona");
        }
        return userId;
    }

    private static boolean hasOnlyHousekeepingRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(SecurityRoles.ROLE_PREFIX + SecurityRoles.HOUSEKEEPING))
                && auth.getAuthorities().stream().noneMatch(a ->
                a.getAuthority().equals(SecurityRoles.ROLE_PREFIX + SecurityRoles.RECEPTIONIST)
                        || a.getAuthority().equals(SecurityRoles.ROLE_PREFIX + SecurityRoles.ADMIN));
    }

    private void requireAssignableUser(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", userId));
        if (!user.isEnabled() || (user.getRole() != UserRole.HOUSEKEEPING && user.getRole() != UserRole.RECEPTIONIST)) {
            throw new BadRequestException("housekeeping.invalid_assignee",
                    "Solo se pueden asignar tareas a personal de limpieza o recepción activo");
        }
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
