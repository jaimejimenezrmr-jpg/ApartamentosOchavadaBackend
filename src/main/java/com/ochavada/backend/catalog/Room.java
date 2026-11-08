package com.ochavada.backend.catalog;

import com.ochavada.backend.common.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Unidad física (apartamento concreto). Es cada fila del rack de disponibilidad. */
@Entity
@Table(name = "room")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(name = "number", nullable = false, length = 10)
    private String number;

    @Column(name = "name", length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "floor")
    private Integer floor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoomStatus status;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "housekeeping_status", nullable = false, length = 20)
    private HousekeepingStatus housekeepingStatus;

    @Setter(AccessLevel.NONE)
    @Column(name = "housekeeping_updated_at")
    private Instant housekeepingUpdatedAt;

    @Column(name = "beds24_unit_id")
    private Integer beds24UnitId;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public Room(RoomType roomType, String number) {
        this.roomType = roomType;
        this.number = number;
        this.status = RoomStatus.ACTIVE;
        this.housekeepingStatus = HousekeepingStatus.CLEAN;
    }

    public void changeHousekeepingStatus(HousekeepingStatus newStatus, Instant now) {
        this.housekeepingStatus = newStatus;
        this.housekeepingUpdatedAt = now;
    }
}
