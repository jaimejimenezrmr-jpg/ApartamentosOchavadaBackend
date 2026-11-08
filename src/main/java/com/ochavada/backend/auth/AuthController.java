package com.ochavada.backend.auth;

import com.ochavada.backend.auth.AuthService.AuthSession;
import com.ochavada.backend.auth.dto.CurrentUserResponse;
import com.ochavada.backend.auth.dto.LoginRequest;
import com.ochavada.backend.auth.dto.TokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autenticación del personal del backoffice.
 * <ul>
 *     <li>{@code POST /login}: email + contraseña → access token (cuerpo) + refresh token (cookie).</li>
 *     <li>{@code POST /refresh}: rota el refresh token de la cookie y emite un nuevo access token.</li>
 *     <li>{@code POST /logout}: revoca la sesión y elimina la cookie.</li>
 *     <li>{@code GET /me}: datos del usuario autenticado.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieFactory cookieFactory;

    public AuthController(AuthService authService, RefreshTokenCookieFactory cookieFactory) {
        this.authService = authService;
        this.cookieFactory = cookieFactory;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthSession session = authService.login(request, ClientInfo.from(httpRequest));
        return tokenResponse(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest httpRequest) {
        AuthSession session = authService.refresh(readRefreshCookie(httpRequest), ClientInfo.from(httpRequest));
        return tokenResponse(session);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(readRefreshCookie(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clear().toString())
                .build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return CurrentUserResponse.from(authService.currentUser(UUID.fromString(jwt.getSubject())));
    }

    private ResponseEntity<TokenResponse> tokenResponse(AuthSession session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookieFactory
                        .create(session.refreshToken().value(), session.refreshToken().expiresAt())
                        .toString())
                .body(TokenResponse.from(session));
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> cookieFactory.cookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
