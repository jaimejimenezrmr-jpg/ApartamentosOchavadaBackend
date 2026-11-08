package com.ochavada.backend.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface RoomBlockRepository extends JpaRepository<RoomBlock, UUID> {

    /** Bloqueos que solapan con el intervalo semiabierto [from, to). */
    @Query("""
            select b from RoomBlock b join fetch b.room r
             where b.startDate < :to and b.endDate > :from
               and (:roomId is null or r.id = :roomId)
             order by r.number asc, b.startDate asc
            """)
    List<RoomBlock> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("roomId") UUID roomId);

    @Query("""
            select count(b) > 0 from RoomBlock b
             where b.room.id = :roomId and b.startDate < :to and b.endDate > :from
            """)
    boolean existsOverlapping(@Param("roomId") UUID roomId, @Param("from") LocalDate from,
                              @Param("to") LocalDate to);
}
