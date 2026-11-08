package com.ochavada.backend.ses;

import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.dto.SesReportDtos.ReportDetail;
import com.ochavada.backend.ses.dto.SesReportDtos.ReportSummary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Seguimiento de las comunicaciones a SES.HOSPEDAJES (recepción y administración). */
@RestController
@RequestMapping("/api/v1/backoffice")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class SesReportController {

    private final SesReportQueryService service;

    public SesReportController(SesReportQueryService service) {
        this.service = service;
    }

    /** Por defecto, las comunicaciones abiertas (pendientes, enviadas, rechazadas, con error o en borrador). */
    @GetMapping("/ses/reports")
    public PageResponse<ReportSummary> list(@RequestParam(required = false) Set<ReportStatus> status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/ses/reports/{id}")
    public ReportDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    /** Regenera la comunicación con los datos actuales y la vuelve a enviar. */
    @PostMapping("/ses/reports/{id}/submit")
    public ReportDetail resubmit(@PathVariable UUID id) {
        return service.resubmit(id);
    }

    @GetMapping("/reservations/{id}/ses-reports")
    public List<ReportSummary> forReservation(@PathVariable UUID id) {
        return service.forReservation(id);
    }
}
