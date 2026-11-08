package com.ochavada.backend.guestportal;

import com.ochavada.backend.common.web.RateLimiter;
import com.ochavada.backend.guestportal.GuestAccessService.GuestSession;
import com.ochavada.backend.guestportal.dto.GuestExchangeRequest;
import com.ochavada.backend.guestportal.dto.GuestStayResponse;
import com.ochavada.backend.guestportal.dto.GuestTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal del huésped.
 * <ul>
 *     <li>{@code POST /api/v1/guest/auth/exchange}: canje del enlace del email por un token de portal (público).</li>
 *     <li>{@code GET /api/v1/guest/stay}: resumen de la estancia (token de huésped).</li>
 * </ul>
 */
@RestController
public class GuestPortalController {

    private final GuestAccessService accessService;
    private final GuestStayService stayService;
    private final RateLimiter rateLimiter;

    public GuestPortalController(GuestAccessService accessService, GuestStayService stayService,
                                 RateLimiter rateLimiter) {
        this.accessService = accessService;
        this.stayService = stayService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/v1/guest/auth/exchange")
    public ResponseEntity<GuestTokenResponse> exchange(@Valid @RequestBody GuestExchangeRequest request,
                                                       HttpServletRequest httpRequest) {
        rateLimiter.check(RateLimiter.Bucket.GUEST_EXCHANGE, httpRequest.getRemoteAddr());
        GuestSession session = accessService.exchange(request.token());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new GuestTokenResponse(session.token().value(), "Bearer", session.token().expiresInSeconds(),
                        session.token().expiresAt(), session.reservationCode()));
    }

    @GetMapping("/api/v1/guest/stay")
    public ResponseEntity<GuestStayResponse> stay() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stayService.currentStay());
    }
}
