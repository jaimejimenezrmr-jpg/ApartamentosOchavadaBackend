package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.beds24.Beds24AdminService.RemoteRoom;
import com.ochavada.backend.channel.beds24.Beds24AdminService.Status;
import com.ochavada.backend.channel.beds24.Beds24BookingImporter.ImportResult;
import com.ochavada.backend.channel.beds24.Beds24PollService.PollSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administración del channel manager (solo ADMIN). La vinculación de tipologías, unidades y planes se
 * hace en el catálogo ({@code beds24RoomId}, {@code beds24UnitId}, {@code channelRateCode = priceN}).
 */
@RestController
@RequestMapping("/api/v1/admin/channel/beds24")
public class Beds24AdminController {

    private final Beds24AdminService service;

    public Beds24AdminController(Beds24AdminService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Status status() {
        return service.status();
    }

    @PostMapping("/connect")
    public Status connect(@Valid @RequestBody ConnectRequest request) {
        return service.connect(request.inviteCode());
    }

    @GetMapping("/rooms")
    public List<RemoteRoom> rooms() {
        return service.remoteRooms();
    }

    /** Reenvía inventario, precios y restricciones de todo el horizonte. */
    @PostMapping("/sync")
    public Map<String, Integer> sync() {
        return Map.of("roomTypesEnqueued", service.fullSync());
    }

    /** Sondeo inmediato de reservas modificadas. */
    @PostMapping("/poll")
    public PollSummary poll() {
        return service.poll();
    }

    /** Importación inicial de las reservas futuras ya existentes en Beds24. */
    @PostMapping("/import-upcoming")
    public PollSummary importUpcoming() {
        return service.importUpcoming();
    }

    @PostMapping("/bookings/{bookingId}/import")
    public Map<String, ImportResult> importBooking(@PathVariable long bookingId) {
        return Map.of("result", service.importBooking(bookingId));
    }

    public record ConnectRequest(@NotBlank @Size(max = 500) String inviteCode) {
    }
}
