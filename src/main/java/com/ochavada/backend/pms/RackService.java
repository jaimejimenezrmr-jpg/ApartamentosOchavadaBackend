package com.ochavada.backend.pms;

import com.ochavada.backend.catalog.Room;
import com.ochavada.backend.catalog.RoomBlock;
import com.ochavada.backend.catalog.RoomBlockRepository;
import com.ochavada.backend.catalog.RoomRepository;
import com.ochavada.backend.catalog.RoomStatus;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.pms.dto.RackResponse;
import com.ochavada.backend.pms.dto.RackResponse.RackBlock;
import com.ochavada.backend.pms.dto.RackResponse.RackBooking;
import com.ochavada.backend.pms.dto.RackResponse.RackRoom;
import com.ochavada.backend.pms.dto.RackResponse.RackRoomType;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Construye el rack con tres consultas (unidades, reservas, bloqueos), sin N+1. */
@Service
public class RackService {

    static final int MAX_DAYS = 62;

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final RoomBlockRepository blockRepository;

    public RackService(RoomRepository roomRepository, ReservationRepository reservationRepository,
                       RoomBlockRepository blockRepository) {
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
        this.blockRepository = blockRepository;
    }

    @Transactional(readOnly = true)
    public RackResponse rack(LocalDate from, LocalDate to) {
        if (!to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new BadRequestException("validation.date_range",
                    "El rack admite rangos de 1 a " + MAX_DAYS + " días");
        }
        List<Room> rooms = roomRepository.findAllByOrderByNumberAsc().stream()
                .filter(r -> r.getStatus() != RoomStatus.INACTIVE)
                .toList();
        List<Reservation> reservations = reservationRepository.findForRack(from, to);
        Map<UUID, List<RackBooking>> bookingsByRoom = reservations.stream()
                .filter(r -> r.getRoom() != null)
                .collect(Collectors.groupingBy(r -> r.getRoom().getId(),
                        Collectors.mapping(RackService::toBooking, Collectors.toList())));
        List<RackBooking> unassigned = reservations.stream()
                .filter(r -> r.getRoom() == null)
                .map(RackService::toBooking)
                .toList();
        Map<UUID, List<RackBlock>> blocksByRoom = blockRepository.findOverlapping(from, to, null).stream()
                .collect(Collectors.groupingBy(b -> b.getRoom().getId(),
                        Collectors.mapping(RackService::toBlock, Collectors.toList())));

        Map<RoomType, List<RackRoom>> byType = new LinkedHashMap<>();
        rooms.stream()
                .sorted(Comparator.comparingInt((Room r) -> r.getRoomType().getDisplayOrder())
                        .thenComparing(r -> r.getRoomType().getName())
                        .thenComparing(Room::getNumber))
                .forEach(room -> byType.computeIfAbsent(room.getRoomType(), t -> new ArrayList<>()).add(new RackRoom(
                        room.getId(), room.getNumber(), room.getName(), room.getStatus(), room.getHousekeepingStatus(),
                        bookingsByRoom.getOrDefault(room.getId(), List.of()),
                        blocksByRoom.getOrDefault(room.getId(), List.of()))));

        List<RackRoomType> types = byType.entrySet().stream()
                .map(e -> new RackRoomType(e.getKey().getId(), e.getKey().getName(), e.getValue()))
                .toList();
        return new RackResponse(from, to, types, unassigned);
    }

    private static RackBooking toBooking(Reservation r) {
        return new RackBooking(r.getId(), r.getCode(), r.getStatus(), r.getChannel(), r.getHolder().fullName(),
                r.getCheckInDate(), r.getCheckOutDate(), r.getAdults(), r.getChildren(), r.balanceDue(),
                r.getRoomType().getId());
    }

    private static RackBlock toBlock(RoomBlock b) {
        return new RackBlock(b.getId(), b.getStartDate(), b.getEndDate(), b.getBlockType(), b.getReason());
    }
}
