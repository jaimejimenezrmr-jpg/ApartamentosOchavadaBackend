package com.ochavada.backend.housekeeping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Punto de la lista de comprobación tal como se aplicó a una limpieza concreta (copia de la plantilla). */
@Entity
@Table(name = "housekeeping_task_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousekeepingTaskCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private HousekeepingTask task;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "section", length = 60)
    private String section;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "photo_required", nullable = false)
    private boolean photoRequired;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "checked_by_user_id")
    private UUID checkedByUserId;

    @Column(name = "comment", length = 500)
    private String comment;

    HousekeepingTaskCheck(HousekeepingTask task, int position, String section, String label, boolean photoRequired) {
        this.task = task;
        this.position = position;
        this.section = section;
        this.label = label;
        this.photoRequired = photoRequired;
    }

    public boolean isChecked() {
        return checkedAt != null;
    }

    void mark(boolean checked, String newComment, UUID userId, Instant now) {
        if (checked) {
            if (checkedAt == null) {
                checkedAt = now;
                checkedByUserId = userId;
            }
        } else {
            checkedAt = null;
            checkedByUserId = null;
        }
        this.comment = newComment;
    }

    void reset() {
        checkedAt = null;
        checkedByUserId = null;
    }
}
