package com.ochavada.backend.checkout;

import com.ochavada.backend.checkout.dto.CheckoutRequest;
import com.ochavada.backend.checkout.dto.CheckoutResponse;
import com.ochavada.backend.checkout.dto.CheckoutStatusResponse;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout público de la web.
 * <ul>
 *     <li>{@code POST /api/v1/public/checkout} — paso 1: retención + PaymentIntent. Cabecera opcional
 *     {@code Idempotency-Key} (recomendada: un UUID por intento de reserva).</li>
 *     <li>{@code GET /api/v1/public/checkout/{code}} — estado (cabecera {@code X-Checkout-Token}).</li>
 *     <li>{@code POST /api/v1/public/checkout/{code}/cancel} — abandono (cabecera {@code X-Checkout-Token}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/public/checkout")
public class CheckoutController {

    public static final String CHECKOUT_TOKEN_HEADER = "X-Checkout-Token";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final CheckoutService service;
    private final RateLimiter rateLimiter;

    public CheckoutController(CheckoutService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> start(
            @Valid @RequestBody CheckoutRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("checkout.invalid_idempotency_key",
                    "Idempotency-Key debe tener entre 8 y 100 caracteres alfanuméricos, '-' o '_'");
        }
        rateLimiter.check(RateLimiter.Bucket.CHECKOUT, httpRequest.getRemoteAddr());
        CheckoutResponse response = service.start(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/public/checkout/" + response.reservationCode()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<CheckoutStatusResponse> status(
            @PathVariable String code,
            @RequestHeader(name = CHECKOUT_TOKEN_HEADER, required = false) String checkoutToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(code, checkoutToken));
    }

    @PostMapping("/{code}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable String code,
            @RequestHeader(name = CHECKOUT_TOKEN_HEADER, required = false) String checkoutToken) {
        service.cancel(code, checkoutToken);
        return ResponseEntity.noContent().build();
    }
}
