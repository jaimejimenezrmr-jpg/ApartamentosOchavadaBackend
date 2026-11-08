package com.ochavada.backend.reservation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByCode(String code);

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    boolean existsByCode(String code);

    @EntityGraph(attributePaths = {"holder", "roomType", "room"})
    Optional<Reservation> findByBeds24BookingId(Long beds24BookingId);

    /**
     * Guarda el identificador asignado por Beds24 sin pasar por la entidad: no incrementa la versión y
     * no provoca conflictos de concurrencia optimista con recepción.
     */
    @Modifying
    @Query(value = "update reservation set beds24_booking_id = :bookingId where id = :id and beds24_booking_id is null",
            nativeQuery = true)
    int linkBeds24Booking(@Param("id") UUID id, @Param("bookingId") long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findForUpdate(@Param("id") UUID id);

    /**
     * Listado del backoffice: reservas que se solapan con {@code [from, to)}, filtradas por estado y por
     * un término (localizador, nombre o email del titular) ya en minúsculas y con comodines.
     */
    @Query(value = """
            select r from Reservation r join fetch r.holder h join fetch r.roomType rt left join fetch r.room ro
             where r.checkOutDate > :from and r.checkInDate < :to
               and r.status in :statuses
               and (lower(r.code) like :term
                    or lower(concat(h.firstName, ' ', h.lastName1, ' ', coalesce(h.lastName2, ''))) like :term
                    or lower(coalesce(h.email, '')) like :term)
            """,
            countQuery = """
            select count(r) from Reservation r join r.holder h
             where r.checkOutDate > :from and r.checkInDate < :to
               and r.status in :statuses
               and (lower(r.code) like :term
                    or lower(concat(h.firstName, ' ', h.lastName1, ' ', coalesce(h.lastName2, ''))) like :term
                    or lower(coalesce(h.email, '')) like :term)
            """)
    Page<Reservation> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("statuses") Collection<ReservationStatus> statuses, @Param("term") String term,
                             Pageable pageable);

    /** Reservas visibles en el rack (todas salvo canceladas) que se solapan con {@code [from, to)}. */
    @Query("""
            select r from Reservation r join fetch r.holder left join fetch r.room
             where r.checkOutDate > :from and r.checkInDate < :to
               and r.status <> com.ochavada.backend.reservation.ReservationStatus.CANCELLED
             order by r.checkInDate asc
            """)
    List<Reservation> findForRack(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"holder", "roomType", "room"})
    List<Reservation> findByCheckInDateAndStatusInOrderByCode(LocalDate checkInDate,
                                                              Collection<ReservationStatus> statuses);

    @EntityGraph(attributePaths = {"holder", "roomType", "room"})
    List<Reservation> findByCheckOutDateAndStatusInOrderByCode(LocalDate checkOutDate,
                                                               Collection<ReservationStatus> statuses);

    long countByStatus(ReservationStatus status);

    /** Retenciones caducadas, de la más antigua a la más reciente. */
    @Query("""
            select r.id from Reservation r
             where r.status = com.ochavada.backend.reservation.ReservationStatus.PENDING_PAYMENT
               and r.holdExpiresAt <= :now
             order by r.holdExpiresAt asc
            """)
    List<UUID> findExpiredHoldIds(@Param("now") Instant now, Limit limit);
}
