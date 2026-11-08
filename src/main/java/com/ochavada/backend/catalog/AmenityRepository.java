package com.ochavada.backend.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    List<Amenity> findAllByOrderByCategoryAscNameAsc();

    @Query(value = "select count(*) > 0 from room_type_amenity where amenity_id = :id", nativeQuery = true)
    boolean isInUse(@Param("id") UUID id);
}
