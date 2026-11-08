package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.beds24.Beds24Dtos.Booking;
import com.ochavada.backend.channel.beds24.Beds24Dtos.BookingsPage;
import com.ochavada.backend.channel.beds24.Beds24Dtos.Property;
import com.ochavada.backend.channel.beds24.Beds24Dtos.RoomCalendar;
import feign.FeignException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Fachada sobre {@link Beds24ApiClient}: traduce los fallos HTTP a {@link Beds24Exception}, descarta el
 * access token si Beds24 lo rechaza y comprueba el resultado elemento a elemento de las escrituras
 * (Beds24 responde 200/201 aunque alguno de los elementos falle).
 */
@Slf4j
@Component
public class Beds24Gateway {

    /** Todos los estados, para que las cancelaciones también se reciban. */
    static final List<String> ALL_STATUSES = List.of("confirmed", "new", "request", "cancelled", "black", "inquiry");
    private static final int MAX_PAGES = 50;
    private static final DateTimeFormatter MODIFIED_FROM = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Beds24ApiClient client;
    private final Beds24TokenManager tokenManager;

    public Beds24Gateway(Beds24ApiClient client, Beds24TokenManager tokenManager) {
        this.client = client;
        this.tokenManager = tokenManager;
    }

    public Optional<Booking> booking(long bookingId) {
        BookingsPage page = call("consulta de la reserva " + bookingId,
                () -> client.bookingsById(bookingId, ALL_STATUSES));
        if (page == null || page.data() == null) {
            return Optional.empty();
        }
        return page.data().stream().filter(b -> b.id() != null && b.id() == bookingId).findFirst();
    }

    /** Reservas creadas, modificadas o canceladas desde {@code since}. */
    public List<Booking> bookingsModifiedSince(Instant since) {
        String from = MODIFIED_FROM.format(since.truncatedTo(ChronoUnit.SECONDS));
        return paged("reservas modificadas", page -> client.bookingsModifiedSince(from, ALL_STATUSES, page));
    }

    /** Reservas confirmadas con llegada a partir de {@code arrivalFrom} (importación inicial). */
    public List<Booking> bookingsArrivingFrom(LocalDate arrivalFrom) {
        return paged("reservas futuras", page -> client.bookingsArrivingFrom(arrivalFrom.toString(),
                List.of("confirmed", "new"), page));
    }

    /**
     * Crea o modifica una reserva en Beds24.
     *
     * @return identificador de la reserva en Beds24 (nuevo o el mismo)
     */
    public Optional<Long> saveBooking(Map<String, Object> booking) {
        JsonNode response = call("envío de reserva", () -> client.saveBookings(List.of(booking)));
        JsonNode item = requireSuccess(response, "envío de reserva");
        for (String path : List.of("/new/id", "/modified/id", "/id", "/new/bookId")) {
            JsonNode id = item.at(path);
            if (id.isNumber() || (id.isString() && id.asString().matches("\\d+"))) {
                return Optional.of(id.asLong());
            }
        }
        Object sentId = booking.get("id");
        return sentId instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
    }

    public void updateCalendar(RoomCalendar calendar) {
        JsonNode response = call("sincronización de inventario", () -> client.updateCalendar(List.of(calendar)));
        requireSuccess(response, "sincronización de inventario de la habitación " + calendar.roomId());
    }

    public List<Property> properties() {
        var page = call("consulta de alojamientos", () -> client.properties(true, true));
        return page == null || page.data() == null ? List.of() : page.data();
    }

    // ------------------------------------------------------------------ internos

    private List<Booking> paged(String operation, IntFunction<BookingsPage> fetch) {
        List<Booking> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int current = page;
            BookingsPage response = call(operation, () -> fetch.apply(current));
            if (response == null || response.data() == null) {
                break;
            }
            result.addAll(response.data());
            if (response.pages() == null || !Boolean.TRUE.equals(response.pages().nextPageExists())) {
                break;
            }
            if (page == MAX_PAGES) {
                log.warn("Beds24: {} truncada tras {} páginas", operation, MAX_PAGES);
            }
        }
        return result;
    }

    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (FeignException ex) {
            if (ex.status() == 401 || ex.status() == 403) {
                // Token caducado o revocado: el siguiente intento pedirá uno nuevo.
                tokenManager.invalidate();
            }
            String detail = ex.status() == 429
                    ? "Beds24: límite de créditos de la API agotado durante la " + operation
                    : "Beds24 rechazó la " + operation + " (HTTP " + ex.status() + ")";
            throw new Beds24Exception(detail, ex.status() <= 0 ? null : ex.status(), ex);
        }
    }

    /** Devuelve el primer elemento (las escrituras se envían de una en una) si Beds24 lo aceptó. */
    static JsonNode requireSuccess(JsonNode response, String operation) {
        if (response == null || response.isNull() || response.isMissingNode()) {
            throw new Beds24Exception("Beds24 no devolvió respuesta a la " + operation, null, null);
        }
        JsonNode item = response.isArray() ? (response.isEmpty() ? null : response.get(0)) : response;
        if (item == null) {
            throw new Beds24Exception("Beds24 devolvió una respuesta vacía a la " + operation, null, null);
        }
        JsonNode success = item.get("success");
        if (success != null && !success.asBoolean()) {
            throw new Beds24Exception("Beds24 rechazó la " + operation + ": " + describe(item.get("errors")), null, null);
        }
        JsonNode warnings = item.get("warnings");
        if (warnings != null && !warnings.isEmpty()) {
            log.warn("Beds24, avisos en la {}: {}", operation, describe(warnings));
        }
        return item;
    }

    private static String describe(JsonNode node) {
        if (node == null || node.isNull()) {
            return "sin detalle";
        }
        if (node.isString()) {
            return node.asString();
        }
        List<String> messages = new ArrayList<>();
        for (JsonNode e : node) {
            JsonNode message = e.get("message");
            JsonNode field = e.get("field");
            messages.add((field != null ? field.asString() + ": " : "")
                    + (message != null ? message.asString() : e.toString()));
        }
        return messages.isEmpty() ? node.toString() : String.join("; ", messages);
    }
}
