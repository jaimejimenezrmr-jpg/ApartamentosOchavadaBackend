package com.ochavada.backend.rates.web;

import com.ochavada.backend.rates.RateCalendarService;
import com.ochavada.backend.rates.RatePlanService;
import com.ochavada.backend.rates.RateSeasonService;
import com.ochavada.backend.rates.dto.RateCalendarResponse;
import com.ochavada.backend.rates.dto.RatePlanResponse;
import com.ochavada.backend.rates.dto.RateSeasonResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta de tarifas para recepción (reservas manuales, atención telefónica) y administración. */
@RestController
@RequestMapping("/api/v1/backoffice/rate-plans")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class BackofficeRateController {

    private final RatePlanService ratePlanService;
    private final RateSeasonService seasonService;
    private final RateCalendarService calendarService;

    public BackofficeRateController(RatePlanService ratePlanService, RateSeasonService seasonService,
                                    RateCalendarService calendarService) {
        this.ratePlanService = ratePlanService;
        this.seasonService = seasonService;
        this.calendarService = calendarService;
    }

    @GetMapping
    public List<RatePlanResponse> list(@RequestParam(required = false) UUID roomTypeId) {
        return ratePlanService.list(roomTypeId);
    }

    @GetMapping("/{id}")
    public RatePlanResponse get(@PathVariable UUID id) {
        return ratePlanService.get(id);
    }

    @GetMapping("/{id}/seasons")
    public List<RateSeasonResponse> seasons(@PathVariable UUID id) {
        return seasonService.list(id);
    }

    /** Calendario de {@code from} a {@code to}, ambos incluidos (máximo 366 días). */
    @GetMapping("/{id}/calendar")
    public RateCalendarResponse calendar(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return calendarService.calendar(id, from, to);
    }
}
