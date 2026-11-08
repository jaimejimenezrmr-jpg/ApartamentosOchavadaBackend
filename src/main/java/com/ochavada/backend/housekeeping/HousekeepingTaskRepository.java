package com.ochavada.backend.housekeeping;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HousekeepingTaskRepository extends JpaRepository<HousekeepingTask, UUID> {

    /**
     * Tareas del día más las abiertas de días anteriores (lo que queda pendiente no desaparece del panel).
     */
    @Query("""
            select t from HousekeepingTask t join fetch t.room r
             where (t.scheduledDate = :date
                    or (t.scheduledDate < :date and t.status in (com.ochavada.backend.housekeeping.TaskStatus.PENDING,
                                                                 com.ochavada.backend.housekeeping.TaskStatus.IN_PROGRESS)))
               and (:status is null or t.status = :status)
               and (:assignedUserId is null or t.assignedUserId = :assignedUserId)
             order by t.priority asc, t.scheduledDate asc, r.number asc
            """)
    List<HousekeepingTask> findBoard(@Param("date") LocalDate date, @Param("status") TaskStatus status,
                                     @Param("assignedUserId") UUID assignedUserId);

    boolean existsByRoomIdAndTaskTypeAndScheduledDateAndStatusIn(UUID roomId, TaskType taskType, LocalDate date,
                                                                 List<TaskStatus> statuses);

    long countByStatusIn(List<TaskStatus> statuses);
}
