package com.ochavada.backend.user;

import com.ochavada.backend.security.SecurityRoles;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Vista de {@link AppUser} para el proceso de login de Spring Security. Solo se usa al validar
 * usuario/contraseña; las peticiones posteriores se autentican con el JWT.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

    private final UUID id;
    private final String email;
    private final UserRole role;
    private final boolean enabled;
    private final boolean locked;
    private String passwordHash;

    public AppUserPrincipal(UUID id, String email, String passwordHash, UserRole role, boolean enabled,
                            boolean locked) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.locked = locked;
    }

    public UUID getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_PREFIX + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
