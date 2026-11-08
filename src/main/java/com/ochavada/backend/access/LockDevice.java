package com.ochavada.backend.access;

import com.ochavada.backend.catalog.Room;
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

/** Cerradura TTLock registrada en el sistema. */
@Entity
@Table(name = "lock_device")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LockDevice extends VersionedEntity {

    @Setter(AccessLevel.NONE)
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    /** {@code lockId} de TTLock. */
    @Column(name = "external_lock_id", nullable = false)
    private long externalLockId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_type", nullable = false, length = 20)
    private LockType lockType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "lock_mac", length = 17)
    private String lockMac;

    @Column(name = "gateway_id")
    private Long gatewayId;

    @Column(name = "has_gateway", nullable = false)
    private boolean hasGateway;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Setter(AccessLevel.NONE)
    @Column(name = "battery_updated_at")
    private Instant batteryUpdatedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    public LockDevice(long externalLockId, String name, LockType lockType, Room room) {
        this.provider = "TTLOCK";
        this.externalLockId = externalLockId;
        this.name = name;
        this.lockType = lockType;
        this.room = room;
        this.hasGateway = true;
        this.active = true;
    }

    public void updateBattery(Integer level, Instant at) {
        this.batteryLevel = level == null ? null : Math.clamp(level, 0, 100);
        this.batteryUpdatedAt = at;
    }
}
