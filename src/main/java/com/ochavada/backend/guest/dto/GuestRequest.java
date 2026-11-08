package com.ochavada.backend.guest.dto;

import com.ochavada.backend.guest.DocumentType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Ficha del huésped con los datos del anexo I del RD 933/2021. Los obligatorios para el parte de
 * viajeros se exigen al comunicarlo, no aquí: la ficha puede completarse poco a poco.
 */
public record GuestRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName1,
        @Size(max = 100) String lastName2,
        @Pattern(regexp = "[HMO]", message = "Sexo: H, M u O") String sex,
        @Past LocalDate birthDate,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Nacionalidad ISO 3166-1 alfa-3 (p. ej. ESP)") String nationality,
        DocumentType documentType,
        @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9]*$", message = "El documento solo admite letras y dígitos")
        String documentNumber,
        @Size(max = 30) String documentSupportNumber,
        @PastOrPresent LocalDate documentIssueDate,
        @Email @Size(max = 254) String email,
        @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido") String phone,
        @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido") String phoneLandline,
        @Size(max = 250) String addressLine,
        @Size(max = 250) String addressComplement,
        @Size(max = 20) String postalCode,
        @Size(max = 100) String city,
        @Pattern(regexp = "^[0-9]{5}$", message = "Código de municipio INE de 5 dígitos") String municipalityCode,
        @Size(max = 100) String province,
        @Pattern(regexp = "^[A-Z]{3}$", message = "País ISO 3166-1 alfa-3 (p. ej. ESP)") String countryCode,
        @Pattern(regexp = "es|en", message = "Idioma no soportado") String preferredLocale,
        Boolean marketingConsent,
        @Size(max = 5000) String notes,
        Long version) {

    @AssertTrue(message = "Tipo y número de documento deben indicarse juntos")
    public boolean isDocumentPairConsistent() {
        boolean hasType = documentType != null;
        boolean hasNumber = documentNumber != null && !documentNumber.isBlank();
        return hasType == hasNumber;
    }
}
