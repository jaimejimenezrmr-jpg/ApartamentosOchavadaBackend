package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.RoomService;
import com.ochavada.backend.catalog.dto.RoomResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta de unidades para todo el personal (recepción, limpieza). */
@RestController
@RequestMapping("/api/v1/backoffice/rooms")
public class BackofficeRoomController {

    private final RoomService service;

    public BackofficeRoomController(RoomService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoomResponse> list(@RequestParam(required = false) UUID roomTypeId) {
        return service.list(roomTypeId);
    }

    @GetMapping("/{id}")
    public RoomResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
