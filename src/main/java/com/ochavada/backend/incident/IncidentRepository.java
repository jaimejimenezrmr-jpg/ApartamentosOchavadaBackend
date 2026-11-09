package com.ochavada.backend.incident;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    @Query("""
            select i from Incident i left join fetch i.room r
             where (:status is null or i.status = :status)
               and (:activeOnly = false or i.status in (com.ochavada.backend.incident.IncidentStatus.OPEN,
                                                        com.ochavada.backend.incident.IncidentStatus.IN_PROGRESS))
               and (:priority is null or i.priority = :priority)
               and (:roomId is null or r.id = :roomId)
             order by i.priority asc, i.createdAt asc
            """)
    List<Incident> search(@Param("status") IncidentStatus status, @Param("activeOnly") boolean activeOnly,
                          @Param("priority") IncidentPriority priority, @Param("roomId") UUID roomId);

    List<Incident> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    long countByStatusInAndPriority(Collection<IncidentStatus> statuses, IncidentPriority priority);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    @Query("""
            select i.room.id, count(i) from Incident i
             where i.room is not null and i.status in :statuses group by i.room.id
            """)
    List<Object[]> countActiveByRoom(@Param("statuses") Collection<IncidentStatus> statuses);
}
