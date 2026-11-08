package com.ochavada.backend.catalog.web;

import com.ochavada.backend.catalog.RoomTypeService;
import com.ochavada.backend.catalog.dto.PublicRoomTypeDetail;
import com.ochavada.backend.catalog.dto.PublicRoomTypeSummary;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo público de la web (sin autenticación). Respuestas cacheables durante un minuto por
 * Cloudflare y por el SSR de Angular.
 */
@RestController
@RequestMapping("/api/v1/public/room-types")
public class PublicCatalogController {

    private static final CacheControl CATALOG_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final RoomTypeService service;

    public PublicCatalogController(RoomTypeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PublicRoomTypeSummary>> list() {
        return ResponseEntity.ok().cacheControl(CATALOG_CACHE).body(service.publicCatalog());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PublicRoomTypeDetail> detail(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CATALOG_CACHE).body(service.publicDetail(slug));
    }
}
