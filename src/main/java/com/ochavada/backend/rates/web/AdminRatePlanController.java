package com.ochavada.backend.rates.web;

import com.ochavada.backend.rates.DailyRateService;
import com.ochavada.backend.rates.RatePlanService;
import com.ochavada.backend.rates.RateSeasonService;
import com.ochavada.backend.rates.dto.DailyRateBulkRequest;
import com.ochavada.backend.rates.dto.DailyRateBulkResult;
import com.ochavada.backend.rates.dto.RatePlanRequest;
import com.ochavada.backend.rates.dto.RatePlanResponse;
import com.ochavada.backend.rates.dto.RateSeasonRequest;
import com.ochavada.backend.rates.dto.RateSeasonResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Escritura de tarifas: planes, temporadas y ajustes diarios. Solo ADMIN.
 * Las consultas están en {@link BackofficeRateController}.
 */
@RestController
@RequestMapping("/api/v1/admin/rate-plans")
public class AdminRatePlanController {

    private final RatePlanService ratePlanService;
    private final RateSeasonService seasonService;
    private final DailyRateService dailyRateService;

    public AdminRatePlanController(RatePlanService ratePlanService, RateSeasonService seasonService,
                                   DailyRateService dailyRateService) {
        this.ratePlanService = ratePlanService;
        this.seasonService = seasonService;
        this.dailyRateService = dailyRateService;
    }

    @PostMapping
    public ResponseEntity<RatePlanResponse> create(@Valid @RequestBody RatePlanRequest request) {
        RatePlanResponse created = ratePlanService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/rate-plans/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public RatePlanResponse update(@PathVariable UUID id, @Valid @RequestBody RatePlanRequest request) {
        return ratePlanService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ratePlanService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/seasons")
    public ResponseEntity<RateSeasonResponse> createSeason(@PathVariable UUID id,
                                                           @Valid @RequestBody RateSeasonRequest request) {
        RateSeasonResponse created = seasonService.create(id, request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/rate-plans/" + id + "/seasons")).body(created);
    }

    @PutMapping("/{id}/seasons/{seasonId}")
    public RateSeasonResponse updateSeason(@PathVariable UUID id, @PathVariable UUID seasonId,
                                           @Valid @RequestBody RateSeasonRequest request) {
        return seasonService.update(id, seasonId, request);
    }

    @DeleteMapping("/{id}/seasons/{seasonId}")
    public ResponseEntity<Void> deleteSeason(@PathVariable UUID id, @PathVariable UUID seasonId) {
        seasonService.delete(id, seasonId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/daily-rates")
    public DailyRateBulkResult applyDailyRates(@PathVariable UUID id, @Valid @RequestBody DailyRateBulkRequest request) {
        return dailyRateService.apply(id, request);
    }
}
