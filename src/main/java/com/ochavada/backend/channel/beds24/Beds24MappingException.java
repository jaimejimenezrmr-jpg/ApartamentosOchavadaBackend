package com.ochavada.backend.channel.beds24;

/**
 * La reserva de Beds24 apunta a una habitación sin vincular. El outbox la reintenta con espera creciente:
 * en cuanto se vincule la tipología ({@code beds24RoomId}) se importará sola.
 */
public class Beds24MappingException extends RuntimeException {

    public Beds24MappingException(String message) {
        super(message);
    }
}
