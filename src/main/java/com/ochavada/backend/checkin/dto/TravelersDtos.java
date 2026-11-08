package com.ochavada.backend.checkin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class TravelersDtos {

    private TravelersDtos() {
    }

    /** Todos los viajeros de la reserva; el primero es el titular. */
    public record TravelersRequest(@NotEmpty @Size(max = 20) List<@Valid TravelerData> travelers) {
    }

    /**
     * @param requiredTravelers personas que deben registrarse (adultos + niños de la reserva)
     * @param complete          si los datos permiten enviar el parte de viajeros
     * @param issues            qué falta o es incorrecto (vacío si está completo)
     */
    public record TravelersResponse(
            String reservationCode,
            int requiredTravelers,
            boolean complete,
            Instant onlineCheckinCompletedAt,
            List<TravelerData> travelers,
            List<Issue> issues) {
    }

    /**
     * @param index posición del viajero (0 = titular); -1 para problemas del conjunto
     */
    public record Issue(int index, String field, String message) {
    }
}
