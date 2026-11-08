package com.ochavada.backend.guest;

import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.guest.dto.GuestDetail;
import com.ochavada.backend.guest.dto.GuestRequest;
import com.ochavada.backend.guest.dto.GuestSummary;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Base de datos de huéspedes. Recepción y administración (contiene datos personales). */
@RestController
@RequestMapping("/api/v1/backoffice/guests")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class GuestController {

    private final GuestService service;

    public GuestController(GuestService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<GuestSummary> search(@RequestParam("q") String query,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.search(query, page, size);
    }

    @GetMapping("/{id}")
    public GuestDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public GuestDetail update(@PathVariable UUID id, @Valid @RequestBody GuestRequest request) {
        return service.update(id, request);
    }
}
