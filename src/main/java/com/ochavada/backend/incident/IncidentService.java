package com.ochavada.backend.incident;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.housekeeping.HousekeepingTask;
import com.ochavada.backend.housekeeping.HousekeepingTaskRepository;
import com.ochavada.backend.incident.IncidentDtos.CreateRequest;
import com.ochavada.backend.incident.IncidentDtos.IncidentResponse;
import com.ochavada.backend.incident.IncidentDtos.PriorityRequest;
import com.ochavada.backend.incident.IncidentDtos.StatusRequest;
import com.ochavada.backend.media.MediaFile;
import com.ochavada.backend.media.MediaOwnerType;
import com.ochavada.backend.media.MediaResponse;
import com.ochavada.backend.media.MediaService;
import com.ochavada.backend.operations.OpsCodeGenerator;
import com.ochavada.backend.security.CurrentUser;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Centro de incidencias (primera versión). Cualquier miembro del personal puede reportar una incidencia y
 * adjuntar fotos a las suyas; recepción y administración las priorizan, resuelven y cierran.
 */
@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final RoomRepository roomRepository;
    private final HousekeepingTaskRepository taskRepository;
    private final AppUserRepository userRepository;
    private final MediaService mediaService;
    private final OpsCodeGenerator codes;
    private final AuditService auditService;
    private final Clock clock;

    public IncidentService(IncidentRepository repository, RoomRepository roomRepository,
                           HousekeepingTaskRepository taskRepository, AppUserRepository userRepository,
                           MediaService mediaService, OpsCodeGenerator codes, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
        this.codes = codes;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> search(IncidentStatus status, boolean activeOnly, IncidentPriority priority, UUID roomId) {
        return toResponses(repository.search(status, activeOnly && status == null, priority, roomId));
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> forTask(UUID taskId) {
        return toResponses(repository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID id) {
        return toResponses(List.of(find(id))).getFirst();
    }

    @Transactional
    public IncidentResponse create(CreateRequest request) {
        UUID userId = CurrentUser.staffUserId().orElseThrow(() -> new AccessDeniedException("Se requiere un usuario del personal"));
        Room room = null;
        UUID taskId = null;
        if (request.taskId() != null) {
            HousekeepingTask task = taskRepository.findById(request.taskId())
                    .orElseThrow(() -> new ResourceNotFoundException("tarea", request.taskId()));
            room = task.getRoom();
            taskId = task.getId();
            if (request.roomId() != null && !request.roomId().equals(room.getId())) {
                throw new BadRequestException("incident.room_mismatch", "La unidad no coincide con la de la tarea");
            }
        } else if (request.roomId() != null) {
            room = roomRepository.findById(request.roomId())
                    .orElseThrow(() -> new ResourceNotFoundException("unidad", request.roomId()));
        }
        IncidentOrigin origin;
        if (CurrentUser.isReceptionOrAbove()) {
            origin = request.origin() == null || request.origin() == IncidentOrigin.HOUSEKEEPING
                    ? IncidentOrigin.STAFF : request.origin();
        } else {
            origin = IncidentOrigin.HOUSEKEEPING;
        }
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().trim();
        Incident incident = new Incident(codes.next(OpsCodeGenerator.Kind.INC), room, taskId, request.title().trim(),
                description, request.priority(), origin, userId);
        repository.saveAndFlush(incident);
        auditService.record("incident.created", "incident", incident.getId(), Map.of(
                "code", incident.getCode(), "priority", incident.getPriority().name(),
                "room", room == null ? "" : room.getNumber(), "origin", origin.name()));
        return toResponses(List.of(incident)).getFirst();
    }

    @Transactional
    public IncidentResponse changeStatus(UUID id, StatusRequest request) {
        Incident incident = find(id);
        incident.assertVersion(request.version());
        IncidentStatus from = incident.getStatus();
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        if (request.status() == IncidentStatus.RESOLVED && notes == null && incident.getResolution() == null) {
            throw new BadRequestException("incident.resolution_required", "Indique qué se ha hecho para resolverla");
        }
        incident.moveTo(request.status(), notes, CurrentUser.staffUserId().orElse(null), clock.instant());
        repository.flush();
        auditService.record("incident.status_changed", "incident", id,
                Map.of("code", incident.getCode(), "from", from.name(), "to", incident.getStatus().name()));
        return get(id);
    }

    @Transactional
    public IncidentResponse changePriority(UUID id, PriorityRequest request) {
        Incident incident = find(id);
        incident.assertVersion(request.version());
        IncidentPriority from = incident.getPriority();
        incident.changePriority(request.priority());
        repository.flush();
        auditService.record("incident.priority_changed", "incident", id,
                Map.of("code", incident.getCode(), "from", from.name(), "to", request.priority().name()));
        return get(id);
    }

    @Transactional
    public MediaResponse addPhoto(UUID id, MultipartFile file) {
        Incident incident = find(id);
        requireReporterOrReception(incident);
        if (incident.getStatus() == IncidentStatus.CLOSED || incident.getStatus() == IncidentStatus.CANCELLED) {
            throw new ConflictException("incident.closed", "La incidencia ya está cerrada");
        }
        return MediaResponse.from(mediaService.store(MediaOwnerType.INCIDENT, id, file));
    }

    @Transactional
    public void deletePhoto(UUID id, UUID mediaId) {
        Incident incident = find(id);
        requireReporterOrReception(incident);
        mediaService.delete(MediaOwnerType.INCIDENT, id, mediaId);
    }

    private void requireReporterOrReception(Incident incident) {
        if (!CurrentUser.isReceptionOrAbove()
                && !Objects.equals(CurrentUser.staffUserId().orElse(null), incident.getReportedByUserId())) {
            throw new AccessDeniedException("Solo quien reportó la incidencia o recepción pueden modificar sus fotos");
        }
    }

    private Incident find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("incidencia", id));
    }

    private List<IncidentResponse> toResponses(List<Incident> incidents) {
        Set<UUID> ids = incidents.stream().map(Incident::getId).collect(Collectors.toSet());
        Map<UUID, List<MediaResponse>> photos = new HashMap<>();
        for (MediaFile m : mediaService.list(MediaOwnerType.INCIDENT, ids)) {
            photos.computeIfAbsent(m.getOwnerId(), k -> new java.util.ArrayList<>()).add(MediaResponse.from(m));
        }
        Map<UUID, String> names = names(incidents.stream().map(Incident::getReportedByUserId).filter(Objects::nonNull).toList());
        return incidents.stream()
                .map(i -> IncidentResponse.from(i, names.get(i.getReportedByUserId()), photos.getOrDefault(i.getId(), List.of())))
                .toList();
    }

    private Map<UUID, String> names(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(new HashSet<>(userIds)).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }
}
