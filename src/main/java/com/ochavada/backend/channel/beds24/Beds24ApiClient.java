package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.beds24.Beds24Dtos.BookingsPage;
import com.ochavada.backend.channel.beds24.Beds24Dtos.PropertiesPage;
import com.ochavada.backend.channel.beds24.Beds24Dtos.RoomCalendar;
import com.ochavada.backend.channel.beds24.Beds24Dtos.TokenResponse;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;

/**
 * Cliente declarativo de la API V2 de Beds24. El access token viaja en la cabecera {@code token}, que
 * añade {@link Beds24FeignConfig} (salvo en los endpoints de autenticación).
 */
@FeignClient(name = "beds24", url = "${app.beds24.base-url}", configuration = Beds24FeignConfig.class)
public interface Beds24ApiClient {

    /** Canjea un código de invitación (válido 24 h) por un refresh token. */
    @GetMapping("/authentication/setup")
    TokenResponse setup(@RequestHeader("code") String inviteCode, @RequestHeader("deviceName") String deviceName);

    /** Access token (≈24 h) a partir del refresh token. */
    @GetMapping("/authentication/token")
    TokenResponse token(@RequestHeader("refreshToken") String refreshToken);

    /** Reservas por identificador, en cualquier estado. */
    @GetMapping("/bookings")
    BookingsPage bookingsById(@RequestParam("id") long id, @RequestParam("status") List<String> statuses);

    /** Reservas modificadas desde un instante (sondeo incremental), paginadas. */
    @GetMapping("/bookings")
    BookingsPage bookingsModifiedSince(@RequestParam("modifiedFrom") String modifiedFrom,
                                       @RequestParam("status") List<String> statuses,
                                       @RequestParam("page") int page);

    /** Reservas con llegada a partir de una fecha (importación inicial), paginadas. */
    @GetMapping("/bookings")
    BookingsPage bookingsArrivingFrom(@RequestParam("arrivalFrom") String arrivalFrom,
                                      @RequestParam("status") List<String> statuses,
                                      @RequestParam("page") int page);

    /** Alta (sin {@code id}) o modificación (con {@code id}) de reservas; una respuesta por elemento. */
    @PostMapping("/bookings")
    JsonNode saveBookings(@RequestBody List<Map<String, Object>> bookings);

    @PostMapping("/inventory/rooms/calendar")
    JsonNode updateCalendar(@RequestBody List<RoomCalendar> calendars);

    @GetMapping("/properties")
    PropertiesPage properties(@RequestParam("includeAllRooms") boolean includeAllRooms,
                              @RequestParam("includeUnitDetails") boolean includeUnitDetails);
}
