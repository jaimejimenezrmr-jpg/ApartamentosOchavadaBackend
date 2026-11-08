package com.ochavada.backend.auth;

import com.ochavada.backend.security.AppSecurityProperties;
import com.ochavada.backend.user.AppUserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contador de intentos fallidos y bloqueo temporal de cuentas. Cada operación se confirma en su propia
 * transacción para que el registro persista aunque el login termine en excepción.
 */
@Service
public class LoginAttemptService {

    private final AppUserRepository repository;
    private final AppSecurityProperties.Login properties;
    private final Clock clock;

    public LoginAttemptService(AppUserRepository repository, AppSecurityProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties.login();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String normalizedEmail) {
        repository.findByEmail(normalizedEmail).ifPresent(user ->
                user.registerFailedLogin(properties.maxFailedAttempts(), properties.lockDuration(), clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID userId) {
        repository.findById(userId).ifPresent(user -> user.registerSuccessfulLogin(clock.instant()));
    }
}
