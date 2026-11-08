package com.ochavada.backend.ses;

import com.ochavada.backend.ses.ReportEnums.CommunicationType;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelerReportRepository extends JpaRepository<TravelerReport, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TravelerReport r where r.id = :id")
    Optional<TravelerReport> findForUpdate(@Param("id") UUID id);

    /** Altas vigentes (no anuladas) de un tipo para una reserva, la más reciente primero. */
    @Query("""
            select r from TravelerReport r
             where r.reservation.id = :reservationId and r.communicationType = :type
               and r.operation = com.ochavada.backend.ses.ReportEnums.Operation.A
               and r.status <> com.ochavada.backend.ses.ReportEnums.ReportStatus.CANCELLED
             order by r.createdAt desc
            """)
    List<TravelerReport> findActiveRegistrations(@Param("reservationId") UUID reservationId,
                                                 @Param("type") CommunicationType type);

    List<TravelerReport> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);

    /**
     * Lotes por consultar: comunicaciones enviadas y, también, las ya sustituidas que siguen esperando su
     * código (lo necesita su anulación).
     */
    @Query("""
            select r.id from TravelerReport r
             where r.nextAttemptAt <= :now and r.sesBatchId is not null
               and (r.status = com.ochavada.backend.ses.ReportEnums.ReportStatus.SUBMITTED
                    or (r.status = com.ochavada.backend.ses.ReportEnums.ReportStatus.CANCELLED
                        and r.sesCommunicationCode is null and r.errorCode is null))
             order by r.nextAttemptAt asc
            """)
    List<UUID> findBatchesToCheck(@Param("now") Instant now, Limit limit);

    @Query("""
            select r from TravelerReport r join fetch r.reservation
             where r.status in :statuses
             order by r.deadlineAt asc
            """)
    Page<TravelerReport> findByStatuses(@Param("statuses") Collection<ReportStatus> statuses, Pageable pageable);

    long countByStatusIn(Collection<ReportStatus> statuses);

    long countByStatusInAndDeadlineAtBefore(Collection<ReportStatus> statuses, Instant deadline);
}
