package com.ochavada.backend.auth;

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

    public AuthService(AuthenticationManager authenticationManager, LoginAttemptService loginAttemptService,
                       RefreshTokenService refreshTokenService, JwtTokenService jwtTokenService,
                       AppUserRepository userRepository) {
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

    @Transactional(readOnly = true)
    public AppUser currentUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", userId));
    }

    private IssuedToken accessTokenFor(AppUser user) {
        return jwtTokenService.issueStaffAccessToken(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    public record AuthSession(AppUser user, IssuedToken accessToken, IssuedRefreshToken refreshToken) {
    }
}
