package com.ochavada.backend.payment.webhook;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de webhooks de Stripe ({@code POST /api/v1/webhooks/stripe}). Es público a nivel de
 * Spring Security: la autenticación es la firma {@code Stripe-Signature}, verificada sobre el cuerpo
 * exacto recibido (por eso se lee como {@code String} y no se deserializa).
 */
@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {

    private final StripeWebhookService service;

    public StripeWebhookController(StripeWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String payload,
                                        @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        service.handle(payload, signature);
        return ResponseEntity.ok().build();
    }
}
