package com.ochavada.backend.channel.beds24;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Estructuras de la API V2 de Beds24 (solo los campos utilizados; el resto se ignora). */
public final class Beds24Dtos {

    private Beds24Dtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(String token, Long expiresIn, String refreshToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingsPage(Boolean success, Integer count, Pages pages, List<Booking> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pages(Boolean nextPageExists, String nextPageLink) {
    }

    /**
     * Reserva de Beds24. {@code status}: new, confirmed, request, cancelled, black, inquiry.
     * {@code channel}: airbnb, booking, expedia, direct, vrbo... ({@code apiSource} lleva el nombre legible).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Booking(
            Long id,
            Long propertyId,
            Long roomId,
            Long unitId,
            String status,
            String subStatus,
            LocalDate arrival,
            LocalDate departure,
            Integer numAdult,
            Integer numChild,
            String firstName,
            String lastName,
            String email,
            String phone,
            String mobile,
            String country,
            String lang,
            BigDecimal price,
            BigDecimal commission,
            String channel,
            String apiSource,
            String apiReference,
            String comments,
            String notes,
            String message) {
    }

    /**
     * Elemento del cuerpo de {@code POST /inventory/rooms/calendar}. Cada día del calendario es un mapa
     * ({@code from}, {@code to}, {@code numAvail}, {@code minStay}, {@code maxStay}, {@code override},
     * {@code price1}..{@code price16}) para enviar solo los huecos de precio vinculados.
     */
    public record RoomCalendar(Long roomId, List<Map<String, Object>> calendar) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropertiesPage(Boolean success, List<Property> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(Long id, String name, String currency, List<Room> roomTypes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(Long id, Long propertyId, String name, Integer qty, Integer maxPeople, List<Unit> units) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unit(Long id, String name) {
    }
}
