package com.ochavada.backend.pms;

import com.ochavada.backend.pms.dto.DashboardResponse;
import com.ochavada.backend.pms.dto.RackResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rack de disponibilidad y panel de recepción. */
@RestController
@RequestMapping("/api/v1/backoffice")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class PmsController {

    private final RackService rackService;
    private final DashboardService dashboardService;

    public PmsController(RackService rackService, DashboardService dashboardService) {
        this.rackService = rackService;
        this.dashboardService = dashboardService;
    }

    /** Rack de {@code [from, to)} (máximo 62 días). */
    @GetMapping("/rack")
    public RackResponse rack(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return rackService.rack(from, to);
    }

    /** Panel del día indicado (por defecto, hoy en la zona horaria del alojamiento). */
    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.summary(date);
    }
}
