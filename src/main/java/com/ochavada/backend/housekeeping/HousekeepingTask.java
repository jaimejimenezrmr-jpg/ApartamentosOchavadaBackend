package com.ochavada.backend.housekeeping;

import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.housekeeping.checklist.ChecklistItem;
import com.ochavada.backend.housekeeping.checklist.ChecklistTemplate;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tarea de gobernanza. Ciclo: PENDING → IN_PROGRESS → DONE → VERIFIED (o CANCELLED desde un estado abierto).
 * <p>
 * Una limpieza con lista de comprobación copia los puntos de la plantilla al empezar; solo se puede terminar
 * con todos marcados (y con foto los que la exigen). La inspección puede rechazarla: vuelve a PENDING con el
 * motivo y hay que repetirla.
 */
@Entity
@Table(name = "housekeeping_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousekeepingTask extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    /** 1 = máxima prioridad, 5 = mínima. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "due_by")
    private Instant dueBy;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by_user_id")
    private UUID verifiedByUserId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "checklist_template_id")
    private UUID checklistTemplateId;

    @Column(name = "completed_by_user_id")
    private UUID completedByUserId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by_user_id")
    private UUID rejectedByUserId;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<HousekeepingTaskCheck> checks = new ArrayList<>();

    public HousekeepingTask(Room room, UUID reservationId, TaskType taskType, int priority, LocalDate scheduledDate,
                            Instant dueBy, String notes, UUID createdByUserId) {
        this.room = room;
        this.reservationId = reservationId;
        this.taskType = taskType;
        this.status = TaskStatus.PENDING;
        this.priority = priority;
        this.scheduledDate = scheduledDate;
        this.dueBy = dueBy;
        this.notes = notes;
        this.createdByUserId = createdByUserId;
    }

    public void assignTo(UUID userId) {
        requireOpen();
        this.assignedUserId = userId;
    }

    public List<HousekeepingTaskCheck> getChecks() {
        return Collections.unmodifiableList(checks);
    }

    public Optional<HousekeepingTaskCheck> findCheck(UUID checkId) {
        return checks.stream().filter(c -> c.getId().equals(checkId)).findFirst();
    }

    /**
     * @param template lista aplicable a la unidad; se copia solo la primera vez (tras un rechazo se conserva
     *                 la misma lista, con sus fotos, y se vuelve a marcar)
     */
    public void start(UUID userId, Instant now, ChecklistTemplate template) {
        if (status != TaskStatus.PENDING) {
            throw invalid("iniciar");
        }
        if (assignedUserId == null) {
            assignedUserId = userId;
        }
        if (template != null && checks.isEmpty() && taskType.cleansRoom()) {
            checklistTemplateId = template.getId();
            for (ChecklistItem item : template.getItems()) {
                checks.add(new HousekeepingTaskCheck(this, item.getPosition(), item.getSection(), item.getLabel(),
                        item.isPhotoRequired()));
            }
        }
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = now;
    }

    public void markCheck(HousekeepingTaskCheck check, boolean checked, String comment, UUID userId, Instant now) {
        if (status != TaskStatus.IN_PROGRESS) {
            throw new ConflictException("housekeeping.task_not_started", "Empiece la tarea antes de marcar la lista");
        }
        check.mark(checked, comment, userId, now);
    }

    /**
     * @param missing puntos sin completar (sin marcar o sin la foto obligatoria); con alguno no se puede terminar
     */
    public void complete(UUID userId, String completionNotes, List<String> missing, Instant now) {
        requireOpen();
        if (!missing.isEmpty()) {
            throw new ConflictException("housekeeping.checklist_incomplete",
                    "Faltan puntos de la lista de comprobación: " + String.join(", ", missing),
                    Map.of("missing", missing));
        }
        if (startedAt == null) {
            startedAt = now;
        }
        if (assignedUserId == null) {
            assignedUserId = userId;
        }
        this.status = TaskStatus.DONE;
        this.completedAt = now;
        this.completedByUserId = userId;
        if (completionNotes != null && !completionNotes.isBlank()) {
            this.notes = notes == null ? completionNotes.trim() : notes + "\n" + completionNotes.trim();
        }
    }

    public void verify(UUID userId, Instant now) {
        if (status != TaskStatus.DONE) {
            throw invalid("inspeccionar");
        }
        this.status = TaskStatus.VERIFIED;
        this.verifiedAt = now;
        this.verifiedByUserId = userId;
    }

    /** La inspección no la da por buena: vuelve a pendiente con el motivo y los puntos sin marcar. */
    public void reject(UUID userId, String reason, Instant now) {
        if (status != TaskStatus.DONE) {
            throw invalid("rechazar");
        }
        this.status = TaskStatus.PENDING;
        this.rejectionCount++;
        this.rejectionReason = reason;
        this.rejectedAt = now;
        this.rejectedByUserId = userId;
        this.completedAt = null;
        this.completedByUserId = null;
        checks.forEach(HousekeepingTaskCheck::reset);
    }

    public void cancel() {
        requireOpen();
        this.status = TaskStatus.CANCELLED;
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw new ConflictException("housekeeping.task_closed", "La tarea ya está " + status);
        }
    }

    private ConflictException invalid(String action) {
        return new ConflictException("housekeeping.invalid_transition",
                "No se puede " + action + " una tarea en estado " + status);
    }
}
