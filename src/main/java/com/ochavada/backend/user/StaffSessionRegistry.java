package com.ochavada.backend.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Comprueba en cada petición que el token de un usuario del backoffice sigue siendo válido aunque no haya
 * caducado: el usuario existe, está activo, conserva el rol del token y el token es posterior a la última
 * revocación de sesiones (baja, cambio de rol o de contraseña).
 * <p>
 * Para no consultar la base de datos en cada petición, el estado se guarda en memoria {@value #TTL_SECONDS} s.
 * En esta instancia los cambios se aplican al momento ({@link #evict}); en otras, como mucho tras ese plazo.
 */
@Component
public class StaffSessionRegistry {

    static final long TTL_SECONDS = 30;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);
    private static final int MAX_ENTRIES = 10_000;

    private final AppUserRepository repository;
    private final Clock clock;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public StaffSessionRegistry(AppUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @param issuedAt instante de emisión del token ({@code iat}, con resolución de segundos)
     * @return motivo del rechazo, o vacío si el token es válido
     */
    public Optional<String> rejectionReason(UUID userId, String role, Instant issuedAt) {
        Snapshot user = snapshot(userId);
        if (user == null) {
            return Optional.of("El usuario no existe");
        }
        if (!user.enabled()) {
            return Optional.of("El usuario está desactivado");
        }
        if (!user.role().name().equals(role)) {
            return Optional.of("El rol del usuario ha cambiado");
        }
        if (user.sessionsRevokedAt() != null && issuedAt != null
                && issuedAt.isBefore(user.sessionsRevokedAt().truncatedTo(ChronoUnit.SECONDS))) {
            return Optional.of("La sesión se ha cerrado");
        }
        return Optional.empty();
    }

    /** Llamar tras modificar el usuario para que el cambio se aplique de inmediato en esta instancia. */
    public void evict(UUID userId) {
        cache.remove(userId);
    }

    private Snapshot snapshot(UUID userId) {
        Instant now = clock.instant();
        Snapshot cached = cache.get(userId);
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached;
        }
        Snapshot fresh = repository.findById(userId)
                .map(u -> new Snapshot(u.isEnabled(), u.getRole(), u.getSessionsRevokedAt(), now))
                .orElse(null);
        if (fresh == null) {
            cache.remove(userId);
            return null;
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, fresh);
        return fresh;
    }

    private record Snapshot(boolean enabled, UserRole role, Instant sessionsRevokedAt, Instant loadedAt) {
    }
}
