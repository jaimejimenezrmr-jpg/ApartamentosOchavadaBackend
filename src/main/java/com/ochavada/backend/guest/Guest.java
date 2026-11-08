package com.ochavada.backend.guest;

import com.ochavada.backend.common.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Huésped (ficha de cliente). Contiene los datos de identificación y residencia que exige el
 * RD 933/2021; se completan en el check-in online o en recepción.
 */
@Entity
@Table(name = "guest")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Guest extends VersionedEntity {

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name1", nullable = false, length = 100)
    private String lastName1;

    @Column(name = "last_name2", length = 100)
    private String lastName2;

    /** H, M u O (códigos SES). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sex", length = 1)
    private String sex;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** ISO 3166-1 alfa-3. */
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

    @Setter(AccessLevel.NONE)
    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "phone_landline", length = 30)
    private String phoneLandline;

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

    @Column(name = "province", length = 100)
    private String province;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "preferred_locale", nullable = false, length = 10)
    private String preferredLocale;

    @Column(name = "marketing_consent", nullable = false)
    private boolean marketingConsent;

    @Column(name = "privacy_accepted_at")
    private Instant privacyAcceptedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public Guest(String firstName, String lastName1, String lastName2) {
        this.firstName = firstName;
        this.lastName1 = lastName1;
        this.lastName2 = lastName2;
        this.preferredLocale = "es";
    }

    public void setEmail(String email) {
        this.email = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public String fullName() {
        return lastName2 == null ? firstName + " " + lastName1 : firstName + " " + lastName1 + " " + lastName2;
    }
}
