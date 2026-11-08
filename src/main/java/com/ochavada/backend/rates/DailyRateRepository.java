package com.ochavada.backend.rates;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyRateRepository extends JpaRepository<DailyRate, UUID> {

    /** Ajustes de los planes indicados entre {@code from} y {@code to}, ambos incluidos. */
    @Query("""
            select d from DailyRate d
             where d.ratePlan.id in :planIds and d.stayDate between :from and :to
             order by d.stayDate asc
            """)
    List<DailyRate> findInRange(@Param("planIds") Collection<UUID> planIds, @Param("from") LocalDate from,
                                @Param("to") LocalDate to);
}
