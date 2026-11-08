package com.ochavada.backend.user;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.auth.RefreshTokenService;
import com.ochavada.backend.auth.RevocationReason;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.security.CurrentUser;
import com.ochavada.backend.user.dto.UserDtos.CreateUserRequest;
import com.ochavada.backend.user.dto.UserDtos.UpdateUserRequest;
import com.ochavada.backend.user.dto.UserDtos.UserResponse;
import com.ochavada.backend.user.dto.UserDtos.UserWithTemporaryPassword;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Administración de usuarios del backoffice (solo ADMIN).
 * <ul>
 *     <li>Las altas y los reseteos generan una contraseña temporal que se muestra una sola vez y que el
 *     usuario debe cambiar en su primer acceso.</li>
 *     <li>Los usuarios no se borran (su rastro queda en reservas, cobros y auditoría): se desactivan.</li>
 *     <li>Desactivar, cambiar el rol o resetear la contraseña cierra todas las sesiones del usuario.</li>
 *     <li>Siempre queda al menos un administrador activo, y nadie puede desactivarse ni quitarse el rol a sí mismo.</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokenService;
    private final StaffSessionRegistry sessionRegistry;
    private final AuditService auditService;
    private final Clock clock;

    public UserAdminService(AppUserRepository repository, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                            RefreshTokenService refreshTokenService, StaffSessionRegistry sessionRegistry,
                            AuditService auditService, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokenService = refreshTokenService;
        this.sessionRegistry = sessionRegistry;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        Instant now = clock.instant();
        return repository.findAllByOrderByEnabledDescFullNameAsc().stream().map(u -> UserResponse.from(u, now)).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(find(id), clock.instant());
    }

    @Transactional
    public UserWithTemporaryPassword create(CreateUserRequest request) {
        String email = AppUser.normalizeEmail(request.email());
        if (repository.findByEmail(email).isPresent()) {
            throw new ConflictException("user.duplicate_email", "Ya existe un usuario con el email " + email);
        }
        String temporary = passwordPolicy.generateTemporary();
        Instant now = clock.instant();
        AppUser user = repository.saveAndFlush(AppUser.createWithTemporaryPassword(email, passwordEncoder.encode(temporary),
                request.fullName(), blankToNull(request.phone()), request.role(), now));
        auditService.record("user.created", "user", user.getId(), Map.of("email", email, "role", request.role().name()));
        return new UserWithTemporaryPassword(UserResponse.from(user, now), temporary);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        AppUser user = find(id);
        if (!Objects.equals(request.version(), user.getVersion())) {
            throw new ConflictException("data.concurrent_modification",
                    "El usuario fue modificado por otra persona. Recargue los datos e inténtelo de nuevo",
                    Map.of("currentVersion", user.getVersion()));
        }
        boolean self = isSelf(id);
        boolean disabling = user.isEnabled() && !request.enabled();
        boolean roleChange = user.getRole() != request.role();
        if (self && disabling) {
            throw new ConflictException("user.cannot_disable_self", "No puede desactivar su propio usuario");
        }
        if (self && roleChange) {
            throw new ConflictException("user.cannot_change_own_role", "No puede cambiar su propio rol");
        }
        if (user.getRole() == UserRole.ADMIN && user.isEnabled() && (disabling || roleChange)
                && repository.countByRoleAndEnabledTrue(UserRole.ADMIN) <= 1) {
            throw new ConflictException("user.last_admin", "Debe quedar al menos un administrador activo");
        }

        Instant now = clock.instant();
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!user.getFullName().equals(request.fullName().trim())) {
            changes.put("fullName", request.fullName().trim());
        }
        if (roleChange) {
            changes.put("role", user.getRole().name() + " → " + request.role().name());
        }
        if (user.isEnabled() != request.enabled()) {
            changes.put("enabled", request.enabled());
        }
        user.updateProfile(request.fullName(), request.phone());
        user.changeRole(request.role(), now);
        if (disabling) {
            user.disable(now);
        } else if (request.enabled()) {
            user.enable();
        }
        repository.saveAndFlush(user);
        if (disabling || roleChange) {
            refreshTokenService.revokeAllSessions(id, disabling ? RevocationReason.USER_DISABLED : RevocationReason.ADMIN_REVOKED);
        }
        evictAfterCommit(id);
        auditService.record("user.updated", "user", id, changes);
        return UserResponse.from(find(id), now);
    }

    @Transactional
    public UserWithTemporaryPassword resetPassword(UUID id) {
        if (isSelf(id)) {
            throw new BadRequestException("user.cannot_reset_self",
                    "Para cambiar su propia contraseña use «Mi cuenta»");
        }
        AppUser user = find(id);
        String temporary = passwordPolicy.generateTemporary();
        Instant now = clock.instant();
        user.assignTemporaryPassword(passwordEncoder.encode(temporary), now);
        repository.saveAndFlush(user);
        refreshTokenService.revokeAllSessions(id, RevocationReason.ADMIN_REVOKED);
        evictAfterCommit(id);
        auditService.record("user.password_reset", "user", id, Map.of("email", user.getEmail()));
        return new UserWithTemporaryPassword(UserResponse.from(find(id), now), temporary);
    }

    @Transactional
    public UserResponse unlock(UUID id) {
        AppUser user = find(id);
        user.unlock();
        repository.saveAndFlush(user);
        auditService.record("user.unlocked", "user", id, Map.of("email", user.getEmail()));
        return UserResponse.from(user, clock.instant());
    }

    /** Cierra todas las sesiones abiertas del usuario (p. ej. móvil perdido). */
    @Transactional
    public UserResponse revokeSessions(UUID id) {
        AppUser user = find(id);
        Instant now = clock.instant();
        user.revokeSessions(now);
        repository.saveAndFlush(user);
        int revoked = refreshTokenService.revokeAllSessions(id, RevocationReason.ADMIN_REVOKED);
        evictAfterCommit(id);
        auditService.record("user.sessions_revoked", "user", id, Map.of("refreshTokens", revoked));
        return UserResponse.from(find(id), now);
    }

    private AppUser find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("usuario", id));
    }

    private static boolean isSelf(UUID id) {
        return CurrentUser.staffUserId().map(id::equals).orElse(false);
    }

    /** Tras confirmar: si se invalidara antes, otra petición podría volver a cachear el estado anterior. */
    private void evictAfterCommit(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sessionRegistry.evict(id);
                }
            });
        } else {
            sessionRegistry.evict(id);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
