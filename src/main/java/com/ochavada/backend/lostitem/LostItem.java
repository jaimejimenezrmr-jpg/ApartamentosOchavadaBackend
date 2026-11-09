package com.ochavada.backend.lostitem;

import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.reservation.Reservation;
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

/** Objeto encontrado en un apartamento, vinculado (si se puede) a la reserva del huésped que lo olvidó. */
@Entity
@Table(name = "lost_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LostItem extends VersionedEntity {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "storage_location", length = 120)
    private String storageLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LostItemStatus status;

    @Column(name = "found_at", nullable = false)
    private Instant foundAt;

    @Column(name = "found_by_user_id")
    private UUID foundByUserId;

    @Column(name = "guest_notified_at")
    private Instant guestNotifiedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public LostItem(String code, Room room, Reservation reservation, UUID taskId, String description,
                    String storageLocation, UUID foundByUserId, Instant foundAt) {
        this.code = code;
        this.room = room;
        this.reservation = reservation;
        this.taskId = taskId;
        this.description = description;
        this.storageLocation = storageLocation;
        this.status = LostItemStatus.STORED;
        this.foundByUserId = foundByUserId;
        this.foundAt = foundAt;
    }

    public void update(String newStorageLocation, String newNotes) {
        this.storageLocation = newStorageLocation;
        this.notes = newNotes;
    }

    public void moveTo(LostItemStatus target, Instant now) {
        if (!status.isOpen()) {
            throw new ConflictException("lost_item.closed", "El objeto ya está " + status);
        }
        if (target == LostItemStatus.STORED || target == status) {
            throw new ConflictException("lost_item.invalid_transition", "El objeto ya está " + status);
        }
        if (target == LostItemStatus.GUEST_NOTIFIED) {
            guestNotifiedAt = now;
        } else {
            closedAt = now;
        }
        this.status = target;
    }
}
