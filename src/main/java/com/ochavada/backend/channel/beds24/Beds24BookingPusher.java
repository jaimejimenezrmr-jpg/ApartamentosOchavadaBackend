package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Envía a Beds24 las reservas creadas en este sistema (web, teléfono, mostrador) para que el channel
 * manager las vea y no vuelva a vender esas noches. El localizador propio viaja como {@code apiReference}:
 * así se reconoce el eco cuando Beds24 notifica la reserva de vuelta.
 * <p>
 * Se ejecuta desde el outbox, en su propia transacción. Un bloqueo consultivo por reserva evita que dos
 * instancias creen la misma reserva dos veces en Beds24.
 */
@Slf4j
@Component
public class Beds24BookingPusher {

    private final ReservationRepository repository;
    private final Beds24Gateway gateway;
    private final JdbcClient jdbc;

    public Beds24BookingPusher(ReservationRepository repository, Beds24Gateway gateway, JdbcClient jdbc) {
        this.repository = repository;
        this.gateway = gateway;
        this.jdbc = jdbc;
    }

    /** @return {@code true} si se envió algo a Beds24 */
    public boolean push(UUID reservationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "beds24:push:" + reservationId)
                .query().singleRow();
        Optional<Reservation> found = repository.findById(reservationId);
        if (found.isEmpty()) {
            return false;
        }
        Reservation r = found.get();
        if (!r.isLocallyOriginated() || r.getRoomType().getBeds24RoomId() == null
                || r.getStatus() == ReservationStatus.PENDING_PAYMENT) {
            return false;
        }
        boolean cancelled = r.getStatus() == ReservationStatus.CANCELLED || r.getStatus() == ReservationStatus.NO_SHOW;
        if (cancelled && r.getBeds24BookingId() == null) {
            // Nunca llegó a Beds24: no hay nada que cancelar.
            return false;
        }
        Map<String, Object> booking = cancelled ? cancellation(r) : booking(r);
        Optional<Long> bookingId = gateway.saveBooking(booking);
        if (r.getBeds24BookingId() == null) {
            long id = bookingId.orElseThrow(() -> new Beds24Exception(
                    "Beds24 aceptó la reserva " + r.getCode() + " pero no devolvió su identificador", null, null));
            repository.linkBeds24Booking(r.getId(), id);
            log.info("Reserva {} creada en Beds24 con id {}", r.getCode(), id);
        }
        return true;
    }

    static Map<String, Object> booking(Reservation r) {
        Guest g = r.getHolder();
        Map<String, Object> b = new LinkedHashMap<>();
        if (r.getBeds24BookingId() != null) {
            b.put("id", r.getBeds24BookingId());
        }
        b.put("roomId", r.getRoomType().getBeds24RoomId());
        if (r.getRoom() != null && r.getRoom().getBeds24UnitId() != null) {
            b.put("unitId", r.getRoom().getBeds24UnitId());
        }
        b.put("status", "confirmed");
        b.put("arrival", r.getCheckInDate().toString());
        b.put("departure", r.getCheckOutDate().toString());
        b.put("numAdult", r.getAdults());
        b.put("numChild", r.getChildren());
        b.put("firstName", g.getFirstName());
        b.put("lastName", g.getLastName2() == null ? g.getLastName1() : g.getLastName1() + " " + g.getLastName2());
        if (g.getEmail() != null) {
            b.put("email", g.getEmail());
        }
        if (g.getPhone() != null) {
            b.put("mobile", g.getPhone());
        }
        b.put("lang", r.getLocale());
        b.put("price", r.getTotalAmount());
        b.put("apiReference", r.getCode());
        b.put("referer", "PMS Ochavada · " + r.getChannel().name());
        b.put("notes", "Reserva " + r.getCode() + " gestionada en el PMS de Apartamentos Ochavada");
        return b;
    }

    static Map<String, Object> cancellation(Reservation r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", r.getBeds24BookingId());
        b.put("status", "cancelled");
        return b;
    }
}
