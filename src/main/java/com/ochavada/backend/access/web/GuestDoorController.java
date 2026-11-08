package com.ochavada.backend.access.web;

import com.ochavada.backend.access.DoorKind;
import com.ochavada.backend.access.GuestDoorService;
import com.ochavada.backend.access.dto.DoorDtos.DoorsResponse;
import com.ochavada.backend.access.dto.DoorDtos.UnlockResponse;
import com.ochavada.backend.auth.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Llave virtual del portal del huésped (token de huésped).
 * <ul>
 *     <li>{@code GET /api/v1/guest/doors}: estado de los botones.</li>
 *     <li>{@code POST /api/v1/guest/doors/main-entrance/unlock}: «Abrir Entrada Principal».</li>
 *     <li>{@code POST /api/v1/guest/doors/room/unlock}: «Abrir Mi Habitación».</li>
 *     <li>{@code POST /api/v1/guest/check-in}: autocheck-in el día de llegada.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/guest")
public class GuestDoorController {

    private final GuestDoorService service;

    public GuestDoorController(GuestDoorService service) {
        this.service = service;
    }

    @GetMapping("/doors")
    public ResponseEntity<DoorsResponse> doors() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.doors());
    }

    @PostMapping("/doors/{door}/unlock")
    public ResponseEntity<UnlockResponse> unlock(@PathVariable String door, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.unlock(DoorKind.fromPath(door), ClientInfo.from(request)));
    }

    @PostMapping("/check-in")
    public ResponseEntity<DoorsResponse> selfCheckIn() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.selfCheckIn());
    }
}
