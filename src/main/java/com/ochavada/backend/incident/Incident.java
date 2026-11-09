package com.ochavada.backend.incident;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Avería o problema detectado en un apartamento o zona común. */
@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends VersionedEntity {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 2)
    private IncidentPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private IncidentOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IncidentStatus status;

    @Column(name = "reported_by_user_id")
    private UUID reportedByUserId;

    @Column(name = "resolution", columnDefinition = "text")
    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "closed_at")
    private Instant closedAt;

    public Incident(String code, Room room, UUID taskId, String title, String description, IncidentPriority priority,
                    IncidentOrigin origin, UUID reportedByUserId) {
        this.code = code;
        this.room = room;
        this.taskId = taskId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.origin = origin;
        this.status = IncidentStatus.OPEN;
        this.reportedByUserId = reportedByUserId;
    }

    public void changePriority(IncidentPriority newPriority) {
        requireActive();
        this.priority = newPriority;
    }

    public void moveTo(IncidentStatus target, String notes, UUID userId, Instant now) {
        if (!status.canMoveTo(target)) {
            throw new ConflictException("incident.invalid_transition",
                    "Una incidencia " + status + " no puede pasar a " + target);
        }
        switch (target) {
            case RESOLVED -> {
                resolvedAt = now;
                resolvedByUserId = userId;
                if (notes != null) {
                    resolution = notes;
                }
            }
            case CLOSED -> closedAt = now;
            case IN_PROGRESS -> {
                // Reapertura tras una resolución que no se ha dado por buena.
                resolvedAt = null;
                resolvedByUserId = null;
                if (notes != null) {
                    resolution = resolution == null ? "Reabierta: " + notes : resolution + "\nReabierta: " + notes;
                }
            }
            case CANCELLED -> {
                closedAt = now;
                if (notes != null) {
                    resolution = notes;
                }
            }
            case OPEN -> throw new IllegalStateException("No se vuelve a OPEN");
        }
        this.status = target;
    }

    private void requireActive() {
        if (!IncidentStatus.ACTIVE.contains(status)) {
            throw new ConflictException("incident.closed", "La incidencia ya está " + status);
        }
    }
}
