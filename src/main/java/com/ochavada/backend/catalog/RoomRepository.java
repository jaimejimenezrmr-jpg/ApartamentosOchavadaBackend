package com.ochavada.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    @EntityGraph(attributePaths = "roomType")
    List<Room> findAllByOrderByNumberAsc();

    @EntityGraph(attributePaths = "roomType")
    List<Room> findByRoomTypeIdOrderByNumberAsc(UUID roomTypeId);

    boolean existsByRoomTypeId(UUID roomTypeId);

    Optional<Room> findByRoomTypeIdAndBeds24UnitId(UUID roomTypeId, Integer beds24UnitId);

    boolean existsByNumber(String number);

    boolean existsByNumberAndIdNot(String number, UUID id);
}
