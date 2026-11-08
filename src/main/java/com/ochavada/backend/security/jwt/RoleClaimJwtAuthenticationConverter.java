package com.ochavada.backend.security.jwt;

import com.ochavada.backend.security.SecurityRoles;
import com.ochavada.backend.user.StaffSessionRegistry;
import com.ochavada.backend.user.UserRole;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Traduce un JWT validado en una autenticación de Spring Security, comprobando la coherencia
 * entre el tipo de token y sus roles:
 * <ul>
 *     <li>{@code staff}: exactamente un rol de backoffice.</li>
 *     <li>{@code guest}: únicamente {@code GUEST} y un {@code reservation_id} UUID válido.</li>
 * </ul>
 * Cualquier incoherencia se trata como token inválido (401).
 */
@Component
public class RoleClaimJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Set<String> STAFF_ROLES = Arrays.stream(UserRole.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final StaffSessionRegistry staffSessions;

    public RoleClaimJwtAuthenticationConverter(StaffSessionRegistry staffSessions) {
        this.staffSessions = staffSessions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        TokenType tokenType = TokenType.fromClaim(jwt.getClaimAsString(JwtClaimNames.TOKEN_TYPE))
                .orElseThrow(() -> new InvalidBearerTokenException("Tipo de token desconocido"));
        List<String> roles = readRoles(jwt);

        switch (tokenType) {
            case STAFF -> {
                if (roles.size() != 1 || !STAFF_ROLES.contains(roles.getFirst())) {
                    throw new InvalidBearerTokenException("Roles no válidos para un token de personal");
                }
                // El token puede seguir sin caducar tras una baja, un cambio de rol o de contraseña.
                staffSessions.rejectionReason(parseUuid(jwt.getSubject()), roles.getFirst(), jwt.getIssuedAt())
                        .ifPresent(reason -> {
                            throw new InvalidBearerTokenException(reason);
                        });
                if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(JwtClaimNames.PASSWORD_CHANGE_REQUIRED))) {
                    // Contraseña temporal: solo puede consultar su perfil y cambiarla.
                    return new JwtAuthenticationToken(jwt,
                            List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_PREFIX + SecurityRoles.PASSWORD_CHANGE_PENDING)),
                            jwt.getSubject());
                }
            }
            case GUEST -> {
                if (!roles.equals(List.of(SecurityRoles.GUEST))) {
                    throw new InvalidBearerTokenException("Roles no válidos para un token de huésped");
                }
                requireUuid(jwt.getClaimAsString(JwtClaimNames.RESERVATION_ID));
            }
        }

        Collection<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(SecurityRoles.ROLE_PREFIX + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static List<String> readRoles(Jwt jwt) {
        Object raw = jwt.getClaims().get(JwtClaimNames.ROLES);
        if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new InvalidBearerTokenException("El token no contiene roles");
        }
        return collection.stream()
                .map(value -> {
                    if (!(value instanceof String role) || role.isBlank()) {
                        throw new InvalidBearerTokenException("Formato de roles no válido");
                    }
                    return role;
                })
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidBearerTokenException("Identificador de usuario no válido en el token");
        }
    }

    private static void requireUuid(String value) {
        if (value == null) {
            throw new InvalidBearerTokenException("El token de huésped no contiene la reserva");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("Identificador de reserva no válido en el token");
        }
    }
}
