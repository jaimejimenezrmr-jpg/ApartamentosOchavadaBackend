package com.ochavada.backend.integration.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Reclama el siguiente evento vencido bloqueándolo; {@code SKIP LOCKED} permite varias instancias del
     * backend procesando en paralelo sin entregar dos veces el mismo evento.
     */
    @Query(value = """
            SELECT id FROM outbox_event
             WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> claimNextDue(@Param("now") Instant now);

    long countByDestinationAndStatus(OutboxDestination destination, OutboxEvent.OutboxStatus status);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, String aggregateId);
}
