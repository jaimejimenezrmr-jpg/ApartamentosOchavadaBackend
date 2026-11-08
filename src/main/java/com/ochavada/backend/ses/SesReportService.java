package com.ochavada.backend.ses;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.checkin.TravelerService;
import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.checkin.dto.TravelersDtos.Issue;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.integration.outbox.OutboxDestination;
import com.ochavada.backend.integration.outbox.OutboxService;
import com.ochavada.backend.payment.Payment;
import com.ochavada.backend.payment.PaymentProvider;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.payment.PaymentStatus;
import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationLifecycleEvent;
import com.ochavada.backend.reservation.ReservationOccupant;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.ses.ReportEnums.CommunicationType;
import com.ochavada.backend.ses.ReportEnums.Operation;
import com.ochavada.backend.ses.ReportEnums.PersonRole;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.TravelerReport.ContractData;
import com.ochavada.backend.ses.TravelerReport.PersonSnapshot;
import com.ochavada.backend.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genera las comunicaciones a SES.HOSPEDAJES a partir del ciclo de vida de las reservas
 * (RD 933/2021, plazo de 24 horas):
 * <ul>
 *     <li>Reserva confirmada → alta de reserva de hospedaje (RH).</li>
 *     <li>Check-in → alta de parte de viajeros (PV) con todos los viajeros.</li>
 *     <li>Cambio de fechas/ocupación, corrección de viajeros o cancelación → anulación (B) de la
 *     comunicación vigente y, salvo cancelación, nueva alta.</li>
 * </ul>
 * Todo ocurre en la transacción del cambio de la reserva; el envío lo hace el outbox con reintentos.
 */
@Slf4j
@Service
public class SesReportService {

    public static final String SUBMIT_EVENT = "ses.submit";
    private static final Duration LEGAL_DEADLINE = Duration.ofHours(24);

    private final TravelerReportRepository repository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final TravelerService travelerService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final SesProperties properties;
    private final PropertyProperties property;
    private final Clock clock;

    public SesReportService(TravelerReportRepository repository, ReservationRepository reservationRepository,
                            PaymentRepository paymentRepository, TravelerService travelerService,
                            OutboxService outboxService, AuditService auditService, SesProperties properties,
                            PropertyProperties property, Clock clock) {
        this.repository = repository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.travelerService = travelerService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.properties = properties;
        this.property = property;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReservationEvent(ReservationLifecycleEvent event) {
        Reservation r = reservationRepository.findById(event.reservationId()).orElseThrow();
        switch (event.type()) {
            case CONFIRMED -> {
                if (properties.reportReservations()) {
                    createReservationReport(r);
                }
            }
            case STAY_CHANGED -> {
                if (properties.reportReservations() && supersede(r, CommunicationType.RESERVATION)) {
                    createReservationReport(r);
                }
                if (supersede(r, CommunicationType.TRAVELER_ENTRY)) {
                    createTravelerEntry(r);
                }
            }
            case CANCELLED -> supersede(r, CommunicationType.RESERVATION);
            case CHECKED_IN -> {
                if (repository.findActiveRegistrations(r.getId(), CommunicationType.TRAVELER_ENTRY).isEmpty()) {
                    createTravelerEntry(r);
                }
            }
            case TRAVELERS_UPDATED -> onTravelersUpdated(r);
        }
    }

    /**
     * Reenvío manual desde el backoffice tras corregir datos: regenera la instantánea con los datos
     * actuales y vuelve a ponerla en cola.
     */
    @Transactional
    public TravelerReport resubmit(UUID reportId) {
        TravelerReport report = repository.findForUpdate(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("comunicación SES", reportId));
        if (report.getStatus() == ReportStatus.ACCEPTED || report.getStatus() == ReportStatus.CANCELLED
                || report.getStatus() == ReportStatus.SUBMITTED) {
            throw new ConflictException("ses.not_resubmittable",
                    "La comunicación está " + report.getStatus() + " y no puede reenviarse");
        }
        if (report.getOperation() == Operation.A) {
            refreshSnapshot(report);
        } else {
            report.markPending();
        }
        if (report.getStatus() == ReportStatus.PENDING) {
            enqueue(report);
        }
        auditService.record("ses.report_resubmitted", "traveler_report", reportId,
                Map.of("status", report.getStatus().name()));
        return report;
    }

    // ------------------------------------------------------------------ generación

    private void createReservationReport(Reservation r) {
        TravelerReport report = TravelerReport.registration(r, CommunicationType.RESERVATION,
                establishmentCode(), contract(r, false), clock.instant().plus(LEGAL_DEADLINE),
                CurrentUser.staffUserId().orElse(null));
        repository.save(report);
        refreshSnapshot(report);
        if (report.getStatus() == ReportStatus.PENDING) {
            enqueue(report);
        }
    }

    /**
     * Titular para la reserva (RH): solo los datos que exige la especificación (nombre, apellidos y un
     * contacto). No se copia el resto de la ficha para que un dato incompleto (p. ej. un DNI sin número de
     * soporte) nunca impida confirmar la reserva.
     */
    private static TravelerData reservationHolder(Reservation r) {
        var g = r.getHolder();
        return new TravelerData(g.getFirstName(), g.getLastName1(), g.getLastName2(), null, null, null, null, null,
                null, null, g.getEmail(), g.getPhone(), g.getPhoneLandline(), null, null, null, null, null, null,
                null, null);
    }

    private void createTravelerEntry(Reservation r) {
        TravelerReport report = TravelerReport.registration(r, CommunicationType.TRAVELER_ENTRY,
                establishmentCode(), contract(r, true), clock.instant().plus(LEGAL_DEADLINE),
                CurrentUser.staffUserId().orElse(null));
        repository.save(report);
        refreshSnapshot(report);
        if (report.getStatus() == ReportStatus.PENDING) {
            enqueue(report);
        }
    }

    /** Recalcula contrato y personas; si faltan datos de viajeros, la deja en borrador. */
    private void refreshSnapshot(TravelerReport report) {
        Reservation r = report.getReservation();
        boolean entry = report.getCommunicationType() == CommunicationType.TRAVELER_ENTRY;
        report.applyContract(contract(r, entry));
        if (!entry) {
            TravelerData holder = reservationHolder(r);
            if (isBlank(holder.email()) && isBlank(holder.phone()) && isBlank(holder.phoneLandline())) {
                report.replacePersons(List.of());
                report.markDraft("El titular no tiene teléfono ni correo electrónico (obligatorio para SES)");
                return;
            }
            report.replacePersons(List.of(new PersonSnapshot(r.getHolder().getId(), PersonRole.TI, holder)));
            report.markPending();
            return;
        }
        List<Issue> issues = travelerService.issues(r);
        if (!issues.isEmpty()) {
            report.replacePersons(List.of());
            report.markDraft("Faltan datos de viajeros: " + issues.stream()
                    .map(i -> (i.index() < 0 ? "" : "viajero " + (i.index() + 1) + ": ") + i.message())
                    .collect(Collectors.joining("; ")));
            log.warn("Parte de viajeros de {} en borrador: datos incompletos", r.getCode());
            return;
        }
        List<TravelerData> travelers = travelerService.currentTravelers(r);
        List<UUID> guestIds = guestIdsInOrder(r);
        List<PersonSnapshot> persons = new ArrayList<>();
        for (int i = 0; i < travelers.size(); i++) {
            persons.add(new PersonSnapshot(i < guestIds.size() ? guestIds.get(i) : null, PersonRole.VI, travelers.get(i)));
        }
        report.replacePersons(persons);
        report.markPending();
    }

    private void onTravelersUpdated(Reservation r) {
        List<TravelerReport> active = repository.findActiveRegistrations(r.getId(), CommunicationType.TRAVELER_ENTRY);
        if (active.isEmpty()) {
            return;
        }
        TravelerReport current = active.getFirst();
        switch (current.getStatus()) {
            case DRAFT, REJECTED, ERROR, PENDING -> {
                refreshSnapshot(current);
                if (current.getStatus() == ReportStatus.PENDING) {
                    enqueue(current);
                }
            }
            case SUBMITTED, ACCEPTED -> {
                // Corrección de un parte ya enviado: se anula y se comunica de nuevo.
                if (supersede(r, CommunicationType.TRAVELER_ENTRY)) {
                    createTravelerEntry(r);
                }
            }
            default -> {
                // CANCELLED no aparece entre las altas vigentes.
            }
        }
    }

    /**
     * Retira la comunicación vigente de un tipo: si no llegó a registrarse se cancela sin más; si está
     * registrada (o en curso) se genera su anulación.
     *
     * @return si había una comunicación vigente que retirar
     */
    private boolean supersede(Reservation r, CommunicationType type) {
        List<TravelerReport> active = repository.findActiveRegistrations(r.getId(), type);
        if (active.isEmpty()) {
            return false;
        }
        for (TravelerReport report : active) {
            switch (report.getStatus()) {
                case DRAFT, PENDING, REJECTED, ERROR -> report.markCancelled();
                case SUBMITTED, ACCEPTED -> {
                    TravelerReport cancellation = TravelerReport.cancellationOf(report,
                            clock.instant().plus(LEGAL_DEADLINE), CurrentUser.staffUserId().orElse(null));
                    report.markCancelled();
                    repository.save(cancellation);
                    enqueue(cancellation);
                }
                default -> {
                    // ya cancelada
                }
            }
        }
        return true;
    }

    private void enqueue(TravelerReport report) {
        outboxService.enqueue(OutboxDestination.SES_HOSPEDAJES, "traveler_report", report.getId(), SUBMIT_EVENT,
                Map.of("reportId", report.getId()));
    }

    // ------------------------------------------------------------------ datos de contrato

    /**
     * @param entry {@code true} para el parte de entrada (hora real de llegada); {@code false} para la
     *              reserva (fecha prevista con la hora de check-in)
     */
    private ContractData contract(Reservation r, boolean entry) {
        Instant checkIn = entry && r.getCheckedInAt() != null ? r.getCheckedInAt()
                : r.getCheckInDate().atTime(property.checkInTime()).atZone(property.timeZone()).toInstant();
        Instant checkOut = r.getCheckOutDate().atTime(property.checkOutTime()).atZone(property.timeZone()).toInstant();
        LocalDate contractDate = r.getCreatedAt() == null ? LocalDate.now(clock.withZone(property.timeZone()))
                : r.getCreatedAt().atZone(property.timeZone()).toLocalDate();
        PaymentInfo pay = payment(r);
        return new ContractData(r.getCode(), contractDate, checkIn, checkOut, r.guestCount(), 1, true,
                pay.type(), pay.holder(), pay.identification(), null, pay.date());
    }

    /**
     * Tipo de pago según los códigos SES (catálogo TIPO_PAGO): EFECT efectivo, TARJT tarjeta, PLATF
     * plataforma de pago, TRANS transferencia, DESTI pago en destino.
     */
    private PaymentInfo payment(Reservation r) {
        String holder = r.getHolder().fullName();
        if (r.getPaymentMode() == PaymentMode.OTA_COLLECT) {
            return new PaymentInfo("PLATF", holder, null, null);
        }
        Optional<Payment> last = paymentRepository.findByReservationIdOrderByCreatedAtAsc(r.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED || p.getStatus() == PaymentStatus.PARTIALLY_REFUNDED)
                .max(Comparator.comparing(p -> p.getPaidAt() == null ? Instant.EPOCH : p.getPaidAt()));
        if (last.isEmpty()) {
            return new PaymentInfo("DESTI", holder, null, null);
        }
        Payment p = last.get();
        String type = switch (p.getProvider()) {
            case CASH -> "EFECT";
            case STRIPE, CARD_TERMINAL -> "TARJT";
            case BANK_TRANSFER -> "TRANS";
            case OTA -> "PLATF";
        };
        String identification = p.getProvider() == PaymentProvider.STRIPE || p.getProvider() == PaymentProvider.CARD_TERMINAL
                ? (p.getCardLast4() == null ? "Tarjeta" : "Tarjeta ****" + p.getCardLast4()) : null;
        LocalDate date = p.getPaidAt() == null ? null : p.getPaidAt().atZone(property.timeZone()).toLocalDate();
        return new PaymentInfo(type, holder, identification, date);
    }

    private List<UUID> guestIdsInOrder(Reservation r) {
        List<UUID> ids = new ArrayList<>();
        ids.add(r.getHolder().getId());
        r.getOccupants().stream().filter(o -> !o.isHolder())
                .sorted(Comparator.comparing(ReservationOccupant::getCreatedAt))
                .forEach(o -> ids.add(o.getGuestId()));
        return ids;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String establishmentCode() {
        String code = properties.establishmentCode();
        return code == null || code.isBlank() ? "SIN_CODIGO" : code;
    }

    private record PaymentInfo(String type, String holder, String identification, LocalDate date) {
    }
}
