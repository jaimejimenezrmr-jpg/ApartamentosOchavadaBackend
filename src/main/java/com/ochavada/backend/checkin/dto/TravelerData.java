package com.ochavada.backend.checkin.dto;

import com.ochavada.backend.guest.DocumentType;
import com.ochavada.backend.guest.Guest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Datos de un viajero para el parte de entrada (RD 933/2021, anexo I). Los límites de longitud son los
 * de la especificación del Servicio de Comunicación SES.HOSPEDAJES. Las reglas que dependen de otros
 * campos (edad, tipo de documento, país) las aplica {@link com.ochavada.backend.checkin.TravelerValidator}.
 *
 * @param relationship parentesco con el adulto responsable (obligatorio para menores): códigos SES
 *                     AB, BA, BN, CD, CY, HJ, HR, NI, PM, SB, SG, TI, TU, YN, OT
 */
public record TravelerData(
        @NotBlank @Size(max = 50) String firstName,
        @NotBlank @Size(max = 50) String lastName1,
        @Size(max = 50) String lastName2,
        @Pattern(regexp = "[HMO]", message = "Sexo: H, M u O") String sex,
        @Past LocalDate birthDate,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Nacionalidad ISO 3166-1 alfa-3 (p. ej. ESP)") String nationality,
        DocumentType documentType,
        @Size(max = 15) @Pattern(regexp = "^[A-Za-z0-9]*$", message = "Solo letras y dígitos") String documentNumber,
        @Size(max = 9) @Pattern(regexp = "^[A-Za-z0-9]*$", message = "Solo letras y dígitos") String documentSupportNumber,
        @PastOrPresent LocalDate documentIssueDate,
        @Email @Size(max = 250) String email,
        @Size(max = 20) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$", message = "Teléfono no válido") String phone,
        @Size(max = 20) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$", message = "Teléfono no válido") String phoneLandline,
        @Size(max = 100) String addressLine,
        @Size(max = 100) String addressComplement,
        @Size(max = 20) String postalCode,
        @Size(max = 100) String city,
        @Pattern(regexp = "^[0-9]{5}$", message = "Código de municipio INE de 5 dígitos") String municipalityCode,
        @Size(max = 100) String province,
        @Pattern(regexp = "^[A-Z]{3}$", message = "País ISO 3166-1 alfa-3 (p. ej. ESP)") String countryCode,
        @Pattern(regexp = "AB|BA|BN|CD|CY|HJ|HR|NI|PM|SB|SG|TI|TU|YN|OT", message = "Parentesco no válido")
        String relationship) {

    public static TravelerData from(Guest g, String relationship) {
        return new TravelerData(g.getFirstName(), g.getLastName1(), g.getLastName2(), g.getSex(), g.getBirthDate(),
                g.getNationality(), g.getDocumentType(), g.getDocumentNumber(), g.getDocumentSupportNumber(),
                g.getDocumentIssueDate(), g.getEmail(), g.getPhone(), g.getPhoneLandline(), g.getAddressLine(),
                g.getAddressComplement(), g.getPostalCode(), g.getCity(), g.getMunicipalityCode(), g.getProvince(),
                g.getCountryCode(), relationship);
    }

    /** Vuelca los datos en la ficha del huésped (normalizando mayúsculas del documento). */
    public void applyTo(Guest g) {
        g.setFirstName(firstName.trim());
        g.setLastName1(lastName1.trim());
        g.setLastName2(blankToNull(lastName2));
        g.setSex(sex);
        g.setBirthDate(birthDate);
        g.setNationality(nationality);
        g.setDocumentType(documentType);
        g.setDocumentNumber(upper(documentNumber));
        g.setDocumentSupportNumber(upper(documentSupportNumber));
        g.setDocumentIssueDate(documentIssueDate);
        if (email != null && !email.isBlank()) {
            g.setEmail(email);
        }
        g.setPhone(blankToNull(phone));
        g.setPhoneLandline(blankToNull(phoneLandline));
        g.setAddressLine(blankToNull(addressLine));
        g.setAddressComplement(blankToNull(addressComplement));
        g.setPostalCode(blankToNull(postalCode));
        g.setCity(blankToNull(city));
        g.setMunicipalityCode(municipalityCode);
        g.setProvince(blankToNull(province));
        g.setCountryCode(countryCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
