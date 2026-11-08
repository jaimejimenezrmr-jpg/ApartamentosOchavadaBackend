package com.ochavada.backend.channel.beds24;

import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook de reservas de Beds24 ({@code /api/v1/webhooks/beds24}). Público para Spring Security: se
 * autentica con la cabecera {@value Beds24WebhookService#TOKEN_HEADER}. Admite POST con cuerpo JSON y GET
 * con {@code bookid} (formato de los webhooks antiguos de Beds24).
 */
@RestController
@RequestMapping("/api/v1/webhooks/beds24")
public class Beds24WebhookController {

    private final Beds24WebhookService service;

    public Beds24WebhookController(Beds24WebhookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestBody(required = false) String body,
                                                    @RequestParam(name = "bookid", required = false) String bookId,
                                                    @RequestHeader(name = Beds24WebhookService.TOKEN_HEADER, required = false) String token) {
        return accepted(service.receive(body, bookId, token));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam(name = "bookid", required = false) String bookId,
                                                   @RequestHeader(name = Beds24WebhookService.TOKEN_HEADER, required = false) String token) {
        return accepted(service.receive(null, bookId, token));
    }

    private static ResponseEntity<Map<String, Object>> accepted(Set<Long> bookingIds) {
        return ResponseEntity.ok(Map.of("received", bookingIds));
    }
}
