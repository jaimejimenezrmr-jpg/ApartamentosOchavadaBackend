package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomStatus;
import java.time.Instant;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        UUID roomTypeId,
        String roomTypeName,
        String number,
        String name,
        Integer floor,
        RoomStatus status,
        HousekeepingStatus housekeepingStatus,
        Instant housekeepingUpdatedAt,
        Integer beds24UnitId,
        String notes,
        long version) {

    public static RoomResponse from(Room room) {
        return new RoomResponse(room.getId(), room.getRoomType().getId(), room.getRoomType().getName(),
                room.getNumber(), room.getName(), room.getFloor(), room.getStatus(), room.getHousekeepingStatus(),
                room.getHousekeepingUpdatedAt(), room.getBeds24UnitId(), room.getNotes(), room.getVersion());
    }
}
