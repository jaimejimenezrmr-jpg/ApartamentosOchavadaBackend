package com.ochavada.backend.integration.webhook;

import com.ochavada.backend.integration.webhook.InboundWebhookEvent.WebhookProvider;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboundWebhookEventRepository extends JpaRepository<InboundWebhookEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from InboundWebhookEvent e where e.provider = :provider and e.externalEventId = :eventId")
    Optional<InboundWebhookEvent> findForUpdate(@Param("provider") WebhookProvider provider,
                                                @Param("eventId") String externalEventId);
}
