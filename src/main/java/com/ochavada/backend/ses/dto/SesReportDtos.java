package com.ochavada.backend.ses.dto;

import com.ochavada.backend.ses.ReportEnums.CommunicationType;
import com.ochavada.backend.ses.ReportEnums.Operation;
import com.ochavada.backend.ses.ReportEnums.PersonRole;
import com.ochavada.backend.ses.ReportEnums.ReportStatus;
import com.ochavada.backend.ses.TravelerReport;
import com.ochavada.backend.ses.TravelerReportPerson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SesReportDtos {

    private SesReportDtos() {
    }

    public record ReportSummary(UUID id, UUID reservationId, String reservationCode, CommunicationType type,
                                Operation operation, ReportStatus status, Instant deadlineAt, boolean overdue,
                                String batchId, String communicationCode, String errorCode, String errorMessage,
                                Instant submittedAt, Instant acceptedAt, Instant createdAt) {

        public static ReportSummary from(TravelerReport r, Instant now) {
            boolean overdue = r.getStatus() != ReportStatus.ACCEPTED && r.getStatus() != ReportStatus.CANCELLED
                    && r.getDeadlineAt().isBefore(now);
            return new ReportSummary(r.getId(), r.getReservation().getId(), r.getReservation().getCode(),
                    r.getCommunicationType(), r.getOperation(), r.getStatus(), r.getDeadlineAt(), overdue,
                    r.getSesBatchId(), r.getSesCommunicationCode(), r.getErrorCode(), r.getErrorMessage(),
                    r.getSubmittedAt(), r.getAcceptedAt(), r.getCreatedAt());
        }
    }

    /** Detalle con la instantánea enviada y los mensajes intercambiados (para resolver rechazos). */
    public record ReportDetail(ReportSummary summary, List<Person> persons, String requestXml, String responseXml) {

        public static ReportDetail from(TravelerReport r, Instant now) {
            return new ReportDetail(ReportSummary.from(r, now), r.getPersons().stream().map(Person::from).toList(),
                    r.getRequestPayload(), r.getResponsePayload());
        }
    }

    public record Person(PersonRole role, String fullName, String documentType, String documentNumber,
                         String nationality) {

        static Person from(TravelerReportPerson p) {
            String name = p.getLastName2() == null ? p.getFirstName() + " " + p.getLastName1()
                    : p.getFirstName() + " " + p.getLastName1() + " " + p.getLastName2();
            return new Person(p.getRole(), name, p.getDocumentType() == null ? null : p.getDocumentType().name(),
                    p.getDocumentNumber(), p.getNationality());
        }
    }
}
