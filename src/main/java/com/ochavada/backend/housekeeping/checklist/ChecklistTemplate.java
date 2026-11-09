package com.ochavada.backend.housekeeping.checklist;

import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.common.domain.VersionedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Plantilla de lista de comprobación de limpieza. Puede ser de un tipo de apartamento o general
 * ({@code roomType} nulo): al empezar una limpieza se usa la del tipo y, si no tiene, la general.
 */
@Entity
@Table(name = "checklist_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistTemplate extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_type_id")
    private RoomType roomType;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "checklist_item", joinColumns = @JoinColumn(name = "template_id"))
    @OrderBy("position")
    private List<ChecklistItem> items = new ArrayList<>();

    public ChecklistTemplate(RoomType roomType, String name, boolean active, List<ChecklistItem> items) {
        this.roomType = roomType;
        this.name = name;
        this.active = active;
        this.items.addAll(items);
    }

    public void update(RoomType roomType, String name, boolean active, List<ChecklistItem> newItems) {
        this.roomType = roomType;
        this.name = name;
        this.active = active;
        this.items.clear();
        this.items.addAll(newItems);
    }

    public List<ChecklistItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
