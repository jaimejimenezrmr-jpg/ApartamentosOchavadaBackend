package com.ochavada.backend.notification;

import com.ochavada.backend.guestportal.GuestAccessService;
import com.ochavada.backend.guestportal.GuestAccessService.IssuedAccessLink;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxEvent;
import com.ochavada.backend.integration.outbox.OutboxHandler;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emails de reserva encolados en el outbox.
 * <p>
 * El enlace de acceso del huésped se genera aquí, dentro de la transacción del envío: si el envío falla,
 * el enlace se revierte con ella, y su valor en claro nunca se guarda en base de datos.
 */
@Slf4j
@Component
public class ReservationEmailHandler implements OutboxHandler {

    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";

    private final ReservationRepository reservationRepository;
    private final GuestAccessService guestAccessService;
    private final ReservationEmailComposer composer;
    private final MailGateway mailGateway;
    private final JsonMapper jsonMapper;

    public ReservationEmailHandler(ReservationRepository reservationRepository, GuestAccessService guestAccessService,
                                   ReservationEmailComposer composer, MailGateway mailGateway, JsonMapper jsonMapper) {
        this.reservationRepository = reservationRepository;
        this.guestAccessService = guestAccessService;
        this.composer = composer;
        this.mailGateway = mailGateway;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public OutboxDestination destination() {
        return OutboxDestination.EMAIL;
    }

    @Override
    public void handle(OutboxEvent event) {
        if (!RESERVATION_CONFIRMED.equals(event.getEventType())) {
            throw new IllegalArgumentException("Tipo de email no soportado: " + event.getEventType());
        }
        JsonNode payload = jsonMapper.readTree(event.getPayload());
        UUID reservationId = UUID.fromString(payload.get("reservationId").asString());
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reserva " + reservationId + " inexistente"));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            log.info("Se omite el email de confirmación de {}: estado {}", reservation.getCode(), reservation.getStatus());
            return;
        }
        if (reservation.getHolder().getEmail() == null) {
            log.warn("La reserva {} no tiene email del titular; no se envía confirmación", reservation.getCode());
            return;
        }
        IssuedAccessLink link = guestAccessService.issue(reservation);
        mailGateway.send(composer.confirmation(reservation, link.url()));
        log.info("Email de confirmación enviado para la reserva {}", reservation.getCode());
    }
}
