package com.ochavada.backend.guestportal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestAccessTokenRepository extends JpaRepository<GuestAccessToken, UUID> {

    @Query("select t from GuestAccessToken t join fetch t.reservation where t.tokenHash = :hash")
    Optional<GuestAccessToken> findByTokenHash(@Param("hash") String tokenHash);

    @Query("select t from GuestAccessToken t join fetch t.reservation where t.id = :id")
    Optional<GuestAccessToken> findWithReservation(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update GuestAccessToken t set t.revokedAt = :now where t.reservation.id = :reservationId and t.revokedAt is null")
    int revokeAllForReservation(@Param("reservationId") UUID reservationId, @Param("now") Instant now);
}
