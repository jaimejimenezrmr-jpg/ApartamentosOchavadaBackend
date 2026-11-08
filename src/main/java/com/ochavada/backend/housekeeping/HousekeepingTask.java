package com.ochavada.backend.housekeeping;

import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tarea de gobernanza. Ciclo: PENDING → IN_PROGRESS → DONE → VERIFIED (o CANCELLED desde un estado abierto).
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

    public void start(UUID userId, Instant now) {
        if (status != TaskStatus.PENDING) {
            throw invalid("iniciar");
        }
        if (assignedUserId == null) {
            assignedUserId = userId;
        }
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = now;
    }

    public void complete(String completionNotes, Instant now) {
        requireOpen();
        if (startedAt == null) {
            startedAt = now;
        }
        this.status = TaskStatus.DONE;
        this.completedAt = now;
        if (completionNotes != null && !completionNotes.isBlank()) {
            this.notes = notes == null ? completionNotes.trim() : notes + "\n" + completionNotes.trim();
        }
    }

    public void verify(UUID userId, Instant now) {
        if (status != TaskStatus.DONE) {
            throw invalid("verificar");
        }
        this.status = TaskStatus.VERIFIED;
        this.verifiedAt = now;
        this.verifiedByUserId = userId;
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
