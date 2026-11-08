package com.ochavada.backend.integration.outbox;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entrega los eventos del outbox. Cada evento se reclama con {@code FOR UPDATE SKIP LOCKED} en una
 * transacción externa que solo actualiza su estado; el manejador se ejecuta en una transacción interna
 * independiente para que un fallo revierta sus cambios sin perder el registro del intento.
 */
@Slf4j
@Service
public class OutboxProcessor {

    private final OutboxRepository repository;
    private final Map<OutboxDestination, OutboxHandler> handlers = new EnumMap<>(OutboxDestination.class);
    private final TransactionTemplate outerTx;
    private final TransactionTemplate handlerTx;
    private final Clock clock;

    public OutboxProcessor(OutboxRepository repository, List<OutboxHandler> handlers,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        handlers.forEach(h -> this.handlers.put(h.destination(), h));
        this.outerTx = new TransactionTemplate(transactionManager);
        this.handlerTx = new TransactionTemplate(transactionManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** @return eventos procesados (entregados o fallidos) en esta pasada */
    public int processDue(int maxEvents) {
        int processed = 0;
        while (processed < maxEvents) {
            Boolean handled = outerTx.execute(status -> processNext());
            if (!Boolean.TRUE.equals(handled)) {
                break;
            }
            processed++;
        }
        return processed;
    }

    private boolean processNext() {
        Optional<UUID> claimed = repository.claimNextDue(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        OutboxEvent event = repository.findById(claimed.get()).orElseThrow();
        OutboxHandler handler = handlers.get(event.getDestination());
        if (handler == null) {
            event.markFailed("No hay manejador para el destino " + event.getDestination(), clock.instant());
            log.error("Evento de outbox {} sin manejador para {}", event.getId(), event.getDestination());
            return true;
        }
        try {
            handlerTx.executeWithoutResult(status -> handler.handle(event));
            event.markSent(clock.instant());
        } catch (RuntimeException ex) {
            event.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage(), clock.instant());
            if (event.getStatus() == OutboxEvent.OutboxStatus.DEAD) {
                log.error("Evento de outbox {} ({} → {}) descartado tras {} intentos", event.getId(),
                        event.getEventType(), event.getDestination(), event.getAttempts(), ex);
            } else {
                log.warn("Fallo entregando el evento de outbox {} ({}), intento {}: {}", event.getId(),
                        event.getEventType(), event.getAttempts(), ex.getMessage());
            }
        }
        return true;
    }
}
