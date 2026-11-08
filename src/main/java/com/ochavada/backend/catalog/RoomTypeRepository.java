package com.ochavada.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    List<RoomType> findAllByOrderByDisplayOrderAscNameAsc();

    List<RoomType> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    Optional<RoomType> findBySlugAndActiveTrue(String slug);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    boolean existsByBeds24RoomIdAndIdNot(Long beds24RoomId, UUID id);

    boolean existsByBeds24RoomId(Long beds24RoomId);

    Optional<RoomType> findByBeds24RoomId(Long beds24RoomId);

    List<RoomType> findByBeds24RoomIdIsNotNull();

    /** Tipologías activas con capacidad para el grupo indicado. */
    @Query("""
            select rt from RoomType rt
             where rt.active = true
               and rt.maxAdults >= :adults
               and rt.maxChildren >= :children
               and rt.maxOccupancy >= :adults + :children
             order by rt.displayOrder asc, rt.name asc
            """)
    List<RoomType> findActiveAccommodating(@Param("adults") int adults, @Param("children") int children);
}
