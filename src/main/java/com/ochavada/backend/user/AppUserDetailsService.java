package com.ochavada.backend.user;

import java.time.Clock;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;
    private final Clock clock;

    public AppUserDetailsService(AppUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return repository.findByEmail(AppUser.normalizeEmail(username))
                .map(user -> new AppUserPrincipal(
                        user.getId(),
                        user.getEmail(),
                        user.getPasswordHash(),
                        user.getRole(),
                        user.isEnabled(),
                        user.isLockedAt(clock.instant())))
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
    }
}
