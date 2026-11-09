package com.ochavada.backend.housekeeping.checklist;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Punto de una lista de comprobación (p. ej. «Cama hecha», con foto obligatoria). */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItem {

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "section", length = 60)
    private String section;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "photo_required", nullable = false)
    private boolean photoRequired;

    public ChecklistItem(int position, String section, String label, boolean photoRequired) {
        this.position = position;
        this.section = section;
        this.label = label;
        this.photoRequired = photoRequired;
    }
}
