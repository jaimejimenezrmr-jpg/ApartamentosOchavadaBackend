package com.ochavada.backend.integration.outbox;

import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Encola eventos en la transacción de negocio en curso (obligatoria). */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxService(OutboxRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(OutboxDestination destination, String aggregateType, Object aggregateId,
                               String eventType, Map<String, ?> payload) {
        return repository.save(new OutboxEvent(destination, aggregateType, aggregateId.toString(), eventType,
                jsonMapper.writeValueAsString(payload), clock.instant()));
    }
}
