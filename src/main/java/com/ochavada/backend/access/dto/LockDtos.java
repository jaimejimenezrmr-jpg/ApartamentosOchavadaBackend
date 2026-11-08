package com.ochavada.backend.access.dto;

import com.ochavada.backend.access.LockAccessLog;
import com.ochavada.backend.access.LockDevice;
import com.ochavada.backend.access.LockType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** DTOs de gestión de cerraduras. */
public final class LockDtos {

    private LockDtos() {
    }

    public record LockRequest(
            @NotNull @Positive Long externalLockId,
            @NotBlank @Size(max = 100) String name,
            @NotNull LockType lockType,
            UUID roomId,
            @Pattern(regexp = "^$|^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$", message = "MAC con formato AA:BB:CC:DD:EE:FF")
            String lockMac,
            @Positive Long gatewayId,
            Boolean hasGateway,
            @NotNull Boolean active,
            Long version) {

        @AssertTrue(message = "Las cerraduras de unidad deben indicar roomId; el resto no")
        public boolean isRoomConsistent() {
            return lockType == null || (lockType == LockType.ROOM) == (roomId != null);
        }
    }

    public record LockResponse(UUID id, long externalLockId, String name, LockType lockType, UUID roomId,
                               String roomNumber, String lockMac, Long gatewayId, boolean hasGateway,
                               Integer batteryLevel, Instant batteryUpdatedAt, boolean lowBattery, boolean active,
                               long version) {

        public static LockResponse from(LockDevice l, int lowBatteryThreshold) {
            return new LockResponse(l.getId(), l.getExternalLockId(), l.getName(), l.getLockType(),
                    l.getRoom() == null ? null : l.getRoom().getId(),
                    l.getRoom() == null ? null : l.getRoom().getNumber(), l.getLockMac(), l.getGatewayId(),
                    l.isHasGateway(), l.getBatteryLevel(), l.getBatteryUpdatedAt(),
                    l.getBatteryLevel() != null && l.getBatteryLevel() <= lowBatteryThreshold, l.isActive(),
                    l.getVersion());
        }
    }

    /** Cerradura existente en la cuenta TTLock (descubrimiento para el alta). */
    public record ProviderLock(long externalLockId, String name, String lockMac, Integer batteryLevel,
                               boolean hasGateway, boolean registered) {
    }

    public record AccessLogEntry(Long id, UUID lockDeviceId, UUID reservationId, UUID userId,
                                 LockAccessLog.ActorType actorType, LockAccessLog.Action action,
                                 LockAccessLog.Result result, LockAccessLog.DenialReason denialReason,
                                 Integer providerErrorCode, String providerMessage, Integer latencyMs,
                                 String clientIp, Instant occurredAt) {

        public static AccessLogEntry from(LockAccessLog l) {
            return new AccessLogEntry(l.getId(), l.getLockDeviceId(), l.getReservationId(), l.getUserId(),
                    l.getActorType(), l.getAction(), l.getResult(), l.getDenialReason(), l.getProviderErrorCode(),
                    l.getProviderMessage(), l.getLatencyMs(), l.getClientIp(), l.getOccurredAt());
        }
    }

    public record SyncResult(int updated, int failed) {
    }
}
