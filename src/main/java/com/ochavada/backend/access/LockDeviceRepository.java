package com.ochavada.backend.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LockDeviceRepository extends JpaRepository<LockDevice, UUID> {

    @EntityGraph(attributePaths = "room")
    List<LockDevice> findAllByOrderByLockTypeAscNameAsc();

    List<LockDevice> findByActiveTrue();

    /** Portal del edificio activo (si hubiera varios, el primero por nombre). */
    Optional<LockDevice> findFirstByLockTypeAndActiveTrueOrderByNameAsc(LockType lockType);

    Optional<LockDevice> findFirstByRoomIdAndActiveTrue(UUID roomId);

    boolean existsByExternalLockId(long externalLockId);

    boolean existsByExternalLockIdAndIdNot(long externalLockId, UUID id);

    boolean existsByRoomIdAndActiveTrue(UUID roomId);

    boolean existsByRoomIdAndActiveTrueAndIdNot(UUID roomId, UUID id);
}
