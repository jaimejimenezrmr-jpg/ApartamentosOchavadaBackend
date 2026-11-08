package com.ochavada.backend.availability;

import com.ochavada.backend.availability.dto.AvailabilityResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buscador de disponibilidad de la web pública:
 * {@code GET /api/v1/public/availability?checkIn=2027-07-10&checkOut=2027-07-14&adults=2&children=1}.
 * No se cachea: la disponibilidad cambia con cada reserva.
 */
@RestController
@RequestMapping("/api/v1/public/availability")
public class PublicAvailabilityController {

    private final AvailabilityService service;

    public PublicAvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AvailabilityResponse> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(defaultValue = "2") @Min(1) @Max(20) int adults,
            @RequestParam(defaultValue = "0") @Min(0) @Max(20) int children) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.search(checkIn, checkOut, adults, children));
    }
}
