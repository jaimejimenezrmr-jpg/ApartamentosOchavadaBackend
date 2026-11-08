package com.ochavada.backend.rates;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateSeasonRepository extends JpaRepository<RateSeason, UUID> {

    List<RateSeason> findByRatePlanIdOrderByStartDateAsc(UUID ratePlanId);

    /** Temporadas de los planes indicados que solapan con {@code [from, to)}. */
    @Query("""
            select s from RateSeason s
             where s.ratePlan.id in :planIds and s.startDate < :to and s.endDate > :from
             order by s.startDate asc
            """)
    List<RateSeason> findOverlapping(@Param("planIds") Collection<UUID> planIds, @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query("""
            select count(s) > 0 from RateSeason s
             where s.ratePlan.id = :planId and s.startDate < :to and s.endDate > :from
            """)
    boolean existsOverlap(@Param("planId") UUID planId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select count(s) > 0 from RateSeason s
             where s.ratePlan.id = :planId and s.id <> :excludeId and s.startDate < :to and s.endDate > :from
            """)
    boolean existsOverlapExcluding(@Param("planId") UUID planId, @Param("from") LocalDate from,
                                   @Param("to") LocalDate to, @Param("excludeId") UUID excludeId);
}
