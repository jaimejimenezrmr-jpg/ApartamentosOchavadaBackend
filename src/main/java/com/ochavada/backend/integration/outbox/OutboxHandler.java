package com.ochavada.backend.integration.outbox;

/**
 * Entrega los eventos de un destino. Se ejecuta en su propia transacción: si lanza una excepción, sus
 * cambios en base de datos se revierten y el evento se reintenta más tarde.
 */
public interface OutboxHandler {

    OutboxDestination destination();

    void handle(OutboxEvent event);
}
