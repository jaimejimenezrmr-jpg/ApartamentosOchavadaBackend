package com.ochavada.backend.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomTypePhotoRepository extends JpaRepository<RoomTypePhoto, UUID> {

    /**
     * Quita la marca de portada al resto de fotos antes de asignarla a una nueva. Se ejecuta por separado
     * porque el índice único parcial ({@code ux_room_type_photo_cover}) no es diferible.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RoomTypePhoto p set p.cover = false where p.roomType.id = :roomTypeId and p.id <> :keepId and p.cover = true")
    int clearCoverExcept(@Param("roomTypeId") UUID roomTypeId, @Param("keepId") UUID keepId);
}
