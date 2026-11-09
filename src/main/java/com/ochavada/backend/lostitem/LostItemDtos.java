package com.ochavada.backend.lostitem;

import com.ochavada.backend.media.MediaResponse;
import com.ochavada.backend.reservation.Reservation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LostItemDtos {

    private LostItemDtos() {
    }

    /** @param taskId limpieza en la que se encontró (la unidad se toma de ella) */
    public record CreateRequest(
            UUID roomId,
            UUID taskId,
            @NotBlank @Size(max = 300) String description,
            @Size(max = 120) String storageLocation) {
    }

    public record UpdateRequest(@Size(max = 120) String storageLocation, @Size(max = 4000) String notes,
                                @NotNull Long version) {
    }

    public record StatusRequest(@NotNull LostItemStatus status, @NotNull Long version) {
    }

    public record LostItemResponse(
            UUID id,
            String code,
            UUID roomId,
            String roomNumber,
            UUID reservationId,
            String reservationCode,
            String guestName,
            String guestEmail,
            String guestPhone,
            UUID taskId,
            String description,
            String storageLocation,
            LostItemStatus status,
            Instant foundAt,
            UUID foundByUserId,
            String foundByName,
            Instant guestNotifiedAt,
            Instant closedAt,
            String notes,
            List<MediaResponse> photos,
            long version) {

        static LostItemResponse from(LostItem l, String foundByName, List<MediaResponse> photos) {
            Reservation r = l.getReservation();
            return new LostItemResponse(l.getId(), l.getCode(),
                    l.getRoom() == null ? null : l.getRoom().getId(),
                    l.getRoom() == null ? null : l.getRoom().getNumber(),
                    r == null ? null : r.getId(), r == null ? null : r.getCode(),
                    r == null ? null : r.getHolder().fullName(),
                    r == null ? null : r.getHolder().getEmail(),
                    r == null ? null : r.getHolder().getPhone(),
                    l.getTaskId(), l.getDescription(), l.getStorageLocation(), l.getStatus(), l.getFoundAt(),
                    l.getFoundByUserId(), foundByName, l.getGuestNotifiedAt(), l.getClosedAt(), l.getNotes(), photos,
                    l.getVersion());
        }
    }
}
