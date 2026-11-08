package com.ochavada.backend.ses;

import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.ses.ReportEnums.CommunicationType;
import com.ochavada.backend.ses.ReportEnums.Operation;
import com.ochavada.backend.ses.ReportEnums.PersonRole;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Comunicación a SES.HOSPEDAJES (RD 933/2021): reserva de hospedaje (RH) o parte de viajeros (PV),
 * con la instantánea de los datos enviados y la traza de su envío (lote, código, errores).
 */
@Entity
@Table(name = "traveler_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelerReport extends VersionedEntity {

    private static final int MAX_ERROR = 1000;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(name = "communication_type", nullable = false, length = 20)
    private CommunicationType communicationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 10)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancels_report_id")
    private TravelerReport cancelsReport;

    @Column(name = "establishment_code", nullable = false, length = 20)
    private String establishmentCode;

    @Column(name = "contract_reference", nullable = false, length = 50)
    private String contractReference;

    @Column(name = "contract_date", nullable = false)
    private LocalDate contractDate;

    @Column(name = "check_in_at", nullable = false)
    private Instant checkInAt;

    @Column(name = "check_out_at", nullable = false)
    private Instant checkOutAt;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "num_travelers", nullable = false)
    private int numTravelers;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "num_rooms", nullable = false)
    private int numRooms;

    @Column(name = "internet_included", nullable = false)
    private boolean internetIncluded;

    @Column(name = "payment_type", length = 10)
    private String paymentType;

    @Column(name = "payment_holder", length = 200)
    private String paymentHolder;

    @Column(name = "payment_identification", length = 50)
    private String paymentIdentification;

    @Column(name = "payment_expiry", length = 7)
    private String paymentExpiry;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "ses_batch_id", length = 50)
    private String sesBatchId;

    @Column(name = "ses_communication_code", length = 50)
    private String sesCommunicationCode;

    @Column(name = "request_payload", columnDefinition = "text")
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "text")
    private String responsePayload;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = MAX_ERROR)
    private String errorMessage;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("role ASC")
    private List<TravelerReportPerson> persons = new ArrayList<>();

    /** Alta (A) de una reserva o un parte con los datos de contrato ya calculados. */
    static TravelerReport registration(Reservation reservation, CommunicationType type, String establishmentCode,
                                       ContractData contract, Instant deadline, UUID userId) {
        TravelerReport r = new TravelerReport();
        r.reservation = reservation;
        r.communicationType = type;
        r.operation = Operation.A;
        r.status = ReportStatus.PENDING;
        r.establishmentCode = establishmentCode;
        r.applyContract(contract);
        r.deadlineAt = deadline;
        r.createdByUserId = userId;
        return r;
    }

    /** Anulación (B) de una comunicación previa. */
    static TravelerReport cancellationOf(TravelerReport target, Instant deadline, UUID userId) {
        TravelerReport r = new TravelerReport();
        r.reservation = target.reservation;
        r.communicationType = target.communicationType;
        r.operation = Operation.B;
        r.status = ReportStatus.PENDING;
        r.cancelsReport = target;
        r.establishmentCode = target.establishmentCode;
        r.contractReference = target.contractReference;
        r.contractDate = target.contractDate;
        r.checkInAt = target.checkInAt;
        r.checkOutAt = target.checkOutAt;
        r.numTravelers = target.numTravelers;
        r.numRooms = target.numRooms;
        r.internetIncluded = target.internetIncluded;
        r.deadlineAt = deadline;
        r.createdByUserId = userId;
        return r;
    }

    void applyContract(ContractData c) {
        this.contractReference = c.reference();
        this.contractDate = c.contractDate();
        this.checkInAt = c.checkInAt();
        this.checkOutAt = c.checkOutAt();
        this.numTravelers = c.numTravelers();
        this.numRooms = c.numRooms();
        this.internetIncluded = c.internet();
        this.paymentType = c.paymentType();
        this.paymentHolder = c.paymentHolder();
        this.paymentIdentification = c.paymentIdentification();
        this.paymentExpiry = c.paymentExpiry();
        this.paymentDate = c.paymentDate();
    }

    void replacePersons(List<PersonSnapshot> snapshot) {
        persons.clear();
        for (PersonSnapshot p : snapshot) {
            persons.add(TravelerReportPerson.of(this, p.guestId(), p.role(), p.data()));
        }
    }

    void markDraft(String reason) {
        this.status = ReportStatus.DRAFT;
        this.errorMessage = truncate(reason);
    }

    void markPending() {
        this.status = ReportStatus.PENDING;
        this.errorCode = null;
        this.errorMessage = null;
    }

    void markSubmitted(String batchId, String request, String response, Instant now, Instant nextCheck) {
        this.status = ReportStatus.SUBMITTED;
        this.sesBatchId = batchId;
        this.requestPayload = request;
        this.responsePayload = response;
        this.submittedAt = now;
        this.nextAttemptAt = nextCheck;
        this.attempts++;
        this.errorCode = null;
        this.errorMessage = null;
    }

    void markAccepted(String communicationCode, String response, Instant now) {
        this.status = ReportStatus.ACCEPTED;
        this.sesCommunicationCode = communicationCode;
        this.responsePayload = response;
        this.acceptedAt = now;
        this.nextAttemptAt = null;
    }

    void markRejected(String code, String message, String response) {
        this.status = ReportStatus.REJECTED;
        this.errorCode = code;
        this.errorMessage = truncate(message);
        this.responsePayload = response;
        this.nextAttemptAt = null;
    }

    void markError(String code, String message, String request, String response) {
        this.status = ReportStatus.ERROR;
        this.errorCode = code;
        this.errorMessage = truncate(message);
        if (request != null) {
            this.requestPayload = request;
        }
        this.responsePayload = response;
        this.attempts++;
        this.nextAttemptAt = null;
    }

    /**
     * Cancela la comunicación. Si estaba enviada y aún sin código, se sigue consultando su lote: la
     * anulación que la sustituye necesita ese código.
     */
    void markCancelled() {
        boolean inFlight = sesBatchId != null && sesCommunicationCode == null && errorCode == null;
        this.status = ReportStatus.CANCELLED;
        if (!inFlight) {
            this.nextAttemptAt = null;
        }
    }

    /** Resultado del lote de una comunicación ya sustituida: se guarda sin cambiar su estado. */
    void recordLateResult(String communicationCode, String error, String response) {
        this.sesCommunicationCode = communicationCode;
        if (communicationCode == null) {
            this.errorCode = "REJECTED";
            this.errorMessage = truncate(error);
        }
        this.responsePayload = response;
        this.nextAttemptAt = null;
    }

    /** ¿Enviada y pendiente de que el servicio asigne código o la rechace? */
    boolean awaitsBatchResult() {
        return sesBatchId != null && sesCommunicationCode == null && errorCode == null;
    }

    void scheduleNextCheck(Instant next) {
        this.nextAttemptAt = next;
        this.attempts++;
    }

    void noteDeferred(String reason) {
        this.errorMessage = truncate(reason);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }

    /** Datos de contrato comunes a reserva y parte. */
    record ContractData(String reference, LocalDate contractDate, Instant checkInAt, Instant checkOutAt,
                        int numTravelers, int numRooms, boolean internet, String paymentType, String paymentHolder,
                        String paymentIdentification, String paymentExpiry, LocalDate paymentDate) {
    }

    record PersonSnapshot(UUID guestId, PersonRole role, TravelerData data) {
    }
}
