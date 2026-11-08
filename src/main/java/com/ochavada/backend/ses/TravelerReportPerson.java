package com.ochavada.backend.ses;

import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.guest.DocumentType;
import com.ochavada.backend.ses.ReportEnums.PersonRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Instantánea de los datos de una persona en el momento de la comunicación: lo enviado al Ministerio
 * queda registrado aunque la ficha del huésped cambie después.
 */
@Entity
@Table(name = "traveler_report_person")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelerReportPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private TravelerReport report;

    @Column(name = "guest_id")
    private UUID guestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 2)
    private PersonRole role;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name1", nullable = false, length = 100)
    private String lastName1;

    @Column(name = "last_name2", length = 100)
    private String lastName2;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sex", length = 1)
    private String sex;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "nationality", length = 3)
    private String nationality;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 5)
    private DocumentType documentType;

    @Column(name = "document_number", length = 30)
    private String documentNumber;

    @Column(name = "document_support_number", length = 30)
    private String documentSupportNumber;

    @Column(name = "document_issue_date")
    private LocalDate documentIssueDate;

    @Column(name = "address_line", length = 250)
    private String addressLine;

    @Column(name = "address_complement", length = 250)
    private String addressComplement;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "city", length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "municipality_code", length = 5)
    private String municipalityCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "phone_landline", length = 30)
    private String phoneLandline;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "relationship", length = 2)
    private String relationship;

    static TravelerReportPerson of(TravelerReport report, UUID guestId, PersonRole role, TravelerData d) {
        TravelerReportPerson p = new TravelerReportPerson();
        p.report = report;
        p.guestId = guestId;
        p.role = role;
        p.firstName = d.firstName().trim();
        p.lastName1 = d.lastName1().trim();
        p.lastName2 = trim(d.lastName2());
        p.sex = d.sex();
        p.birthDate = d.birthDate();
        p.nationality = d.nationality();
        p.documentType = d.documentType();
        p.documentNumber = upper(d.documentNumber());
        p.documentSupportNumber = upper(d.documentSupportNumber());
        p.documentIssueDate = d.documentIssueDate();
        p.addressLine = trim(d.addressLine());
        p.addressComplement = trim(d.addressComplement());
        p.postalCode = trim(d.postalCode());
        p.city = trim(d.city());
        p.municipalityCode = d.municipalityCode();
        p.countryCode = d.countryCode();
        p.phone = trim(d.phone());
        p.phoneLandline = trim(d.phoneLandline());
        p.email = trim(d.email());
        p.relationship = d.relationship();
        return p;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
