package com.ochavada.backend.user;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el administrador inicial a partir de {@code APP_BOOTSTRAP_ADMIN_EMAIL} y
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD} si la base de datos no tiene ningún ADMIN.
 * Evita sembrar credenciales en las migraciones SQL.
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminBootstrapProperties properties;
    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminBootstrapRunner(AdminBootstrapProperties properties, AppUserRepository repository,
                                PasswordEncoder passwordEncoder, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.existsByRole(UserRole.ADMIN)) {
            return;
        }
        if (!properties.isConfigured()) {
            log.warn("No existe ningún usuario ADMIN. Defina APP_BOOTSTRAP_ADMIN_EMAIL y "
                    + "APP_BOOTSTRAP_ADMIN_PASSWORD para crear el administrador inicial.");
            return;
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD debe tener al menos "
                    + MIN_PASSWORD_LENGTH + " caracteres");
        }
        String email = AppUser.normalizeEmail(properties.email());
        if (repository.findByEmail(email).isPresent()) {
            throw new IllegalStateException("Ya existe un usuario no administrador con el email " + email);
        }
        AppUser admin = AppUser.create(email, passwordEncoder.encode(properties.password()),
                properties.fullName(), UserRole.ADMIN, clock.instant());
        repository.save(admin);
        log.info("Administrador inicial creado: {}. Elimine las variables APP_BOOTSTRAP_ADMIN_* del entorno.", email);
    }
}
