package com.ochavada.backend.rates;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RatePlanRepository extends JpaRepository<RatePlan, UUID> {

    @EntityGraph(attributePaths = "roomType")
    List<RatePlan> findAllByOrderByRoomTypeDisplayOrderAscNameAsc();

    @EntityGraph(attributePaths = "roomType")
    List<RatePlan> findByRoomTypeIdOrderByNameAsc(UUID roomTypeId);

    List<RatePlan> findByRoomTypeIdInAndActiveTrue(Collection<UUID> roomTypeIds);

    boolean existsByRoomTypeId(UUID roomTypeId);

    boolean existsByRoomTypeIdAndCode(UUID roomTypeId, String code);

    boolean existsByRoomTypeIdAndCodeAndIdNot(UUID roomTypeId, String code, UUID id);

    /** El índice único parcial {@code ux_rate_plan_default} exige desmarcar antes de marcar otro. */
    @Modifying(flushAutomatically = true)
    @Query("update RatePlan p set p.defaultPlan = false where p.roomType.id = :roomTypeId and p.id <> :keepId and p.defaultPlan = true")
    int clearDefaultExcept(@Param("roomTypeId") UUID roomTypeId, @Param("keepId") UUID keepId);
}
