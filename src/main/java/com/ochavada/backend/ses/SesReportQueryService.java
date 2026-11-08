package com.ochavada.backend.ses;

import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.dto.SesReportDtos.ReportDetail;
import com.ochavada.backend.ses.dto.SesReportDtos.ReportSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SesReportQueryService {

    /** Estados que requieren atención o seguimiento por parte de recepción. */
    public static final Set<ReportStatus> OPEN = EnumSet.of(ReportStatus.DRAFT, ReportStatus.PENDING,
            ReportStatus.SUBMITTED, ReportStatus.REJECTED, ReportStatus.ERROR);
    public static final Set<ReportStatus> NEEDS_ACTION = EnumSet.of(ReportStatus.DRAFT, ReportStatus.REJECTED,
            ReportStatus.ERROR);

    private final TravelerReportRepository repository;
    private final SesReportService reportService;
    private final Clock clock;

    public SesReportQueryService(TravelerReportRepository repository, SesReportService reportService, Clock clock) {
        this.repository = repository;
        this.reportService = reportService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportSummary> list(Set<ReportStatus> statuses, int page, int size) {
        Set<ReportStatus> effective = statuses == null || statuses.isEmpty() ? OPEN : statuses;
        Instant now = clock.instant();
        return PageResponse.of(repository.findByStatuses(effective, PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, 100))), r -> ReportSummary.from(r, now));
    }

    @Transactional(readOnly = true)
    public ReportDetail detail(UUID id) {
        return ReportDetail.from(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("comunicación SES", id)), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<ReportSummary> forReservation(UUID reservationId) {
        Instant now = clock.instant();
        return repository.findByReservationIdOrderByCreatedAtAsc(reservationId).stream()
                .map(r -> ReportSummary.from(r, now)).toList();
    }

    @Transactional
    public ReportDetail resubmit(UUID id) {
        return ReportDetail.from(reportService.resubmit(id), clock.instant());
    }

    @Transactional(readOnly = true)
    public long needingAction() {
        return repository.countByStatusIn(NEEDS_ACTION);
    }

    @Transactional(readOnly = true)
    public long overdue() {
        return repository.countByStatusInAndDeadlineAtBefore(OPEN, clock.instant());
    }
}
