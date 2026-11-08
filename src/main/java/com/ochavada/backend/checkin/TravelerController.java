package com.ochavada.backend.checkin;

import com.ochavada.backend.checkin.dto.TravelersDtos.TravelersRequest;
import com.ochavada.backend.checkin.dto.TravelersDtos.TravelersResponse;
import com.ochavada.backend.guestportal.GuestAccessService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Check-in online: el huésped registra a los viajeros desde el portal ({@code /api/v1/guest/travelers});
 * recepción puede hacerlo en el mostrador ({@code /api/v1/backoffice/reservations/{id}/travelers}).
 */
@RestController
public class TravelerController {

    private final TravelerService service;
    private final GuestAccessService guestAccessService;

    public TravelerController(TravelerService service, GuestAccessService guestAccessService) {
        this.service = service;
        this.guestAccessService = guestAccessService;
    }

    @GetMapping("/api/v1/guest/travelers")
    public ResponseEntity<TravelersResponse> guestView() {
        return noStore(service.view(guestAccessService.currentContext().reservationId()));
    }

    @PutMapping("/api/v1/guest/travelers")
    public ResponseEntity<TravelersResponse> guestUpdate(@Valid @RequestBody TravelersRequest request) {
        return noStore(service.update(guestAccessService.currentContext().reservationId(), request, true));
    }

    @GetMapping("/api/v1/backoffice/reservations/{id}/travelers")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<TravelersResponse> staffView(@PathVariable UUID id) {
        return noStore(service.view(id));
    }

    @PutMapping("/api/v1/backoffice/reservations/{id}/travelers")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<TravelersResponse> staffUpdate(@PathVariable UUID id, @Valid @RequestBody TravelersRequest request) {
        return noStore(service.update(id, request, false));
    }

    private static ResponseEntity<TravelersResponse> noStore(TravelersResponse body) {
        // Datos personales: nunca en cachés intermedias.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
