package com.ochavada.backend.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** El email debe llegar normalizado con {@link AppUser#normalizeEmail(String)}. */
    Optional<AppUser> findByEmail(String email);

    boolean existsByRole(UserRole role);

    long countByRoleAndEnabledTrue(UserRole role);

    List<AppUser> findAllByOrderByEnabledDescFullNameAsc();
}
