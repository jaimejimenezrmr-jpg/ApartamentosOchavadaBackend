package com.ochavada.backend.auth;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import java.util.List;
import java.time.Instant;
import java.time.Clock;
import com.ochavada.backend.user.StaffSessionRegistry;
import com.ochavada.backend.user.PasswordPolicy;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.audit.AuditLog.ActorType;
import com.ochavada.backend.auth.RefreshTokenService.IssuedRefreshToken;
import com.ochavada.backend.auth.RefreshTokenService.RotatedSession;
import com.ochavada.backend.auth.dto.LoginRequest;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.security.jwt.IssuedToken;
import com.ochavada.backend.security.jwt.JwtTokenService;
import com.ochavada.backend.user.AppUser;
import com.ochavada.backend.user.AppUserPrincipal;
import com.ochavada.backend.user.AppUserRepository;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de autenticación del personal: login, renovación y cierre de sesión.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttemptService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenService jwtTokenService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final StaffSessionRegistry sessionRegistry;
    private final AuditService auditService;
    private final Clock clock;

    public AuthService(AuthenticationManager authenticationManager, LoginAttemptService loginAttemptService,
                       RefreshTokenService refreshTokenService, JwtTokenService jwtTokenService,
                       AppUserRepository userRepository, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       StaffSessionRegistry sessionRegistry, AuditService auditService, Clock clock) {
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionRegistry = sessionRegistry;
        this.auditService = auditService;
        this.clock = clock;
        this.authenticationManager = authenticationManager;
        this.loginAttemptService = loginAttemptService;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
    }

    public AuthSession login(LoginRequest request, ClientInfo client) {
        String email = AppUser.normalizeEmail(request.email());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        } catch (BadCredentialsException ex) {
            loginAttemptService.recordFailure(email);
            throw new InvalidCredentialsException();
        } catch (AuthenticationException ex) {
            // Cuenta bloqueada, deshabilitada, etc.: misma respuesta para no revelar su estado.
            throw new InvalidCredentialsException();
        }

        UUID userId = ((AppUserPrincipal) authentication.getPrincipal()).getId();
        loginAttemptService.recordSuccess(userId);
        AppUser user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);

        IssuedRefreshToken refreshToken = refreshTokenService.issueNewSession(user, client);
        return new AuthSession(user, accessTokenFor(user), refreshToken);
    }

    public AuthSession refresh(String rawRefreshToken, ClientInfo client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        RotatedSession rotated = refreshTokenService.rotate(rawRefreshToken, client);
        return new AuthSession(rotated.user(), accessTokenFor(rotated.user()), rotated.refreshToken());
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revokeSession(rawRefreshToken);
        }
    }

    /**
     * El usuario cambia su contraseña (obligatorio si era temporal). Se cierran todas sus sesiones, también
     * las de otros dispositivos, y se abre una nueva en el actual.
     */
    @Transactional(noRollbackFor = InvalidCurrentPasswordException.class)
    public AuthSession changePassword(UUID userId, String currentPassword, String newPassword, ClientInfo client) {
        AppUser user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        Instant now = clock.instant();
        if (!user.isEnabled() || user.isLockedAt(now)) {
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // Cuenta como intento fallido: evita adivinar la contraseña con una sesión robada.
            loginAttemptService.recordFailure(user.getEmail());
            throw new InvalidCurrentPasswordException();
        }
        passwordPolicy.validate(newPassword, user.getEmail(), "newPassword");
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException("auth.same_password", "La nueva contraseña debe ser distinta de la actual",
                    Map.of("errors", List.of(Map.of("field", "newPassword", "message", "Debe ser distinta de la actual"))));
        }
        user.changePassword(passwordEncoder.encode(newPassword), now);
        userRepository.saveAndFlush(user);
        refreshTokenService.revokeAllSessions(userId, RevocationReason.PASSWORD_CHANGED);
        auditService.record(ActorType.STAFF, userId, "user.password_changed", "user", userId, Map.of());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionRegistry.evict(userId);
            }
        });
        AppUser updated = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        IssuedRefreshToken refreshToken = refreshTokenService.issueNewSession(updated, client);
        return new AuthSession(updated, accessTokenFor(updated), refreshToken);
    }

    @Transactional(readOnly = true)
    public AppUser currentUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", userId));
    }

    private IssuedToken accessTokenFor(AppUser user) {
        return jwtTokenService.issueStaffAccessToken(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.isPasswordChangeRequired());
    }

    public record AuthSession(AppUser user, IssuedToken accessToken, IssuedRefreshToken refreshToken) {
    }
}
