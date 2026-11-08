package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.BlockType;
import com.ochavada.backend.catalog.RoomBlock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RoomBlockResponse(
        UUID id,
        UUID roomId,
        String roomNumber,
        LocalDate startDate,
        LocalDate endDate,
        BlockType blockType,
        String reason,
        UUID createdByUserId,
        Instant createdAt) {

    public static RoomBlockResponse from(RoomBlock block) {
        return new RoomBlockResponse(block.getId(), block.getRoom().getId(), block.getRoom().getNumber(),
                block.getStartDate(), block.getEndDate(), block.getBlockType(), block.getReason(),
                block.getCreatedByUserId(), block.getCreatedAt());
    }
}
