package com.ochavada.backend.guestportal;

import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.auth.TokenHasher;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.security.jwt.IssuedToken;
import com.ochavada.backend.security.jwt.JwtClaimNames;
import com.ochavada.backend.security.jwt.JwtTokenService;
import com.ochavada.backend.security.jwt.TokenType;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enlaces mágicos del portal del huésped:
 * <ol>
 *     <li>Al confirmarse la reserva se emite un token opaco (256 bits) que se envía por email.</li>
 *     <li>La web lo canjea por un JWT de huésped de corta duración limitado a esa reserva.</li>
 *     <li>Cada petición del portal revalida que el enlace siga vigente: cancelar la reserva o revocar el
 *     enlace corta el acceso de inmediato, aunque el JWT no haya caducado.</li>
 * </ol>
 */
@Service
public class GuestAccessService {

    /** Estados en los que el portal es accesible (la apertura de puertas exige además CHECKED_IN). */
    private static final Set<ReservationStatus> PORTAL_STATUSES = EnumSet.of(
            ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT);

    private final GuestAccessTokenRepository repository;
    private final TokenHasher tokenHasher;
    private final JwtTokenService jwtTokenService;
    private final AuditService auditService;
    private final PropertyProperties property;
    private final GuestPortalProperties portal;
    private final Clock clock;

    public GuestAccessService(GuestAccessTokenRepository repository, TokenHasher tokenHasher,
                              JwtTokenService jwtTokenService, AuditService auditService,
                              PropertyProperties property, GuestPortalProperties portal, Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
        this.jwtTokenService = jwtTokenService;
        this.auditService = auditService;
        this.property = property;
        this.portal = portal;
        this.clock = clock;
    }

    /**
     * Emite un nuevo enlace para la reserva. El valor en claro solo existe en el email.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedAccessLink issue(Reservation reservation) {
        Instant now = clock.instant();
        Instant expiresAt = reservation.getCheckOutDate()
                .atTime(property.checkOutTime())
                .atZone(property.timeZone())
                .toInstant()
                .plus(portal.postCheckoutGrace());
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("La estancia " + reservation.getCode() + " ya ha finalizado");
        }
        String rawToken = tokenHasher.generateToken();
        GuestAccessToken token = repository.save(
                new GuestAccessToken(reservation, tokenHasher.hash(rawToken), now, expiresAt, now));
        return new IssuedAccessLink(token.getId(), portal.accessUrl() + "#acceso=" + rawToken, expiresAt);
    }

    /** Canjea el token del enlace por un JWT de huésped. */
    @Transactional
    public GuestSession exchange(String rawToken) {
        Instant now = clock.instant();
        GuestAccessToken token = repository.findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow(InvalidGuestAccessException::new);
        Reservation reservation = token.getReservation();
        if (!token.isUsableAt(now) || !PORTAL_STATUSES.contains(reservation.getStatus())) {
            throw new InvalidGuestAccessException();
        }
        token.registerUse(now);
        IssuedToken jwt = jwtTokenService.issueGuestAccessToken(token.getId(), reservation.getId(), token.getExpiresAt());
        auditService.record(ActorType.GUEST, null, "guest.portal_login", "reservation", reservation.getId(),
                Map.of("accessId", token.getId().toString(), "useCount", token.getUseCount()));
        return new GuestSession(jwt, reservation.getCode());
    }

    /**
     * Reserva de la petición en curso, validando que el enlace del que procede el JWT siga vigente.
     */
    @Transactional(readOnly = true)
    public Reservation currentReservation() {
        return validatedToken().getReservation();
    }

    /** Identificadores de la sesión del huésped en curso, tras validar que el enlace sigue vigente. */
    @Transactional(readOnly = true)
    public GuestContext currentContext() {
        GuestAccessToken token = validatedToken();
        return new GuestContext(token.getId(), token.getReservation().getId());
    }

    private GuestAccessToken validatedToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)
                || !TokenType.GUEST.claimValue().equals(jwtAuth.getToken().getClaimAsString(JwtClaimNames.TOKEN_TYPE))) {
            throw new InvalidGuestAccessException();
        }
        UUID accessId = UUID.fromString(jwtAuth.getName());
        UUID reservationId = UUID.fromString(jwtAuth.getToken().getClaimAsString(JwtClaimNames.RESERVATION_ID));
        GuestAccessToken token = repository.findWithReservation(accessId).orElseThrow(InvalidGuestAccessException::new);
        Reservation reservation = token.getReservation();
        if (!reservation.getId().equals(reservationId) || !token.isUsableAt(clock.instant())
                || !PORTAL_STATUSES.contains(reservation.getStatus())) {
            throw new InvalidGuestAccessException();
        }
        return token;
    }

    public record GuestContext(UUID accessId, UUID reservationId) {
    }

    /** Revoca todos los enlaces de una reserva (cancelación, cambio de titular...). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAll(UUID reservationId) {
        return repository.revokeAllForReservation(reservationId, clock.instant());
    }

    public record IssuedAccessLink(UUID accessId, String url, Instant expiresAt) {
    }

    public record GuestSession(IssuedToken token, String reservationCode) {
    }
}
