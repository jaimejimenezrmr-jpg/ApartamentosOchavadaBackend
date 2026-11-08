package com.ochavada.backend.reservation.backoffice;

import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.dto.ActionRequests;
import com.ochavada.backend.reservation.backoffice.dto.ManualReservationRequest;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail;
import com.ochavada.backend.reservation.backoffice.dto.ReservationNotesRequest;
import com.ochavada.backend.reservation.backoffice.dto.ReservationSummary;
import com.ochavada.backend.reservation.backoffice.dto.StayChangeRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gestión de reservas en recepción. */
@RestController
@RequestMapping("/api/v1/backoffice")
@PreAuthorize("hasRole('RECEPTIONIST')")
public class BackofficeReservationController {

    private final ReservationQueryService queryService;
    private final ReservationCommandService commandService;
    private final FrontDeskService frontDeskService;
    private final ReservationPaymentService paymentService;

    public BackofficeReservationController(ReservationQueryService queryService,
                                           ReservationCommandService commandService,
                                           FrontDeskService frontDeskService,
                                           ReservationPaymentService paymentService) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.frontDeskService = frontDeskService;
        this.paymentService = paymentService;
    }

    /** Reservas que se solapan con {@code [from, to)}; {@code q} busca por localizador, nombre o email. */
    @GetMapping("/reservations")
    public PageResponse<ReservationSummary> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Set<ReservationStatus> status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.search(from, to, status, q, page, size);
    }

    @GetMapping("/reservations/{id}")
    public ReservationDetail detail(@PathVariable UUID id) {
        return queryService.detail(id);
    }

    @GetMapping("/reservations/by-code/{code}")
    public ReservationDetail byCode(@PathVariable String code) {
        return queryService.detailByCode(code);
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationDetail> create(@Valid @RequestBody ManualReservationRequest request) {
        ReservationDetail created = commandService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/backoffice/reservations/" + created.id())).body(created);
    }

    @PatchMapping("/reservations/{id}")
    public ReservationDetail updateNotes(@PathVariable UUID id, @Valid @RequestBody ReservationNotesRequest request) {
        return commandService.updateNotes(id, request);
    }

    @PutMapping("/reservations/{id}/stay")
    public ReservationDetail changeStay(@PathVariable UUID id, @Valid @RequestBody StayChangeRequest request) {
        return commandService.changeStay(id, request);
    }

    @PutMapping("/reservations/{id}/room")
    public ReservationDetail assignRoom(@PathVariable UUID id, @Valid @RequestBody ActionRequests.RoomAssignment request) {
        return commandService.assignRoom(id, request.roomId());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationDetail cancel(@PathVariable UUID id, @Valid @RequestBody ActionRequests.Cancellation request) {
        return commandService.cancel(id, request.reason().trim());
    }

    @PostMapping("/reservations/{id}/no-show")
    public ReservationDetail noShow(@PathVariable UUID id) {
        return commandService.markNoShow(id);
    }

    @PostMapping("/reservations/{id}/check-in")
    public ReservationDetail checkIn(@PathVariable UUID id,
                                     @RequestBody(required = false) ActionRequests.FrontDeskAction request) {
        return frontDeskService.checkIn(id, request != null && request.forced());
    }

    @PostMapping("/reservations/{id}/check-out")
    public ReservationDetail checkOut(@PathVariable UUID id,
                                      @RequestBody(required = false) ActionRequests.FrontDeskAction request) {
        return frontDeskService.checkOut(id, request != null && request.forced());
    }

    @PostMapping("/reservations/{id}/payments")
    public ReservationDetail registerPayment(@PathVariable UUID id,
                                             @Valid @RequestBody ActionRequests.PropertyPayment request) {
        return paymentService.registerPropertyPayment(id, request);
    }

    /** Reembolsos: solo administración. */
    @PostMapping("/payments/{paymentId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ReservationDetail refund(@PathVariable UUID paymentId, @Valid @RequestBody ActionRequests.Refund request) {
        return paymentService.refund(paymentId, request);
    }
}
