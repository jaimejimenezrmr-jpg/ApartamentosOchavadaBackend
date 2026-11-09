package com.ochavada.backend.housekeeping.checklist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, UUID> {

    @Query("select t from ChecklistTemplate t left join fetch t.roomType order by t.active desc, t.name asc")
    List<ChecklistTemplate> findAllForAdmin();

    @Query("select t from ChecklistTemplate t where t.active = true and t.roomType.id = :roomTypeId")
    Optional<ChecklistTemplate> findActiveForRoomType(@Param("roomTypeId") UUID roomTypeId);

    @Query("select t from ChecklistTemplate t where t.active = true and t.roomType is null")
    Optional<ChecklistTemplate> findActiveGeneral();
}
