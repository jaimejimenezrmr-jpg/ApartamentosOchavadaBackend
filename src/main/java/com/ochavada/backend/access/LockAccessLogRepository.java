package com.ochavada.backend.access;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LockAccessLogRepository extends JpaRepository<LockAccessLog, Long> {

    Page<LockAccessLog> findByLockDeviceIdAndOccurredAtBetween(UUID lockDeviceId, Instant from, Instant to,
                                                               Pageable pageable);

    Page<LockAccessLog> findByReservationId(UUID reservationId, Pageable pageable);
}
