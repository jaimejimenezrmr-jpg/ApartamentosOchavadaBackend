package com.ochavada.backend.checkin;

import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.checkin.dto.TravelersDtos.Issue;
import com.ochavada.backend.guest.DocumentType;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reglas del parte de viajeros (PV) del Servicio de Comunicación SES.HOSPEDAJES v3.1.2:
 * <ul>
 *     <li>Fecha de nacimiento siempre; tipo y número de documento para mayores de edad.</li>
 *     <li>Número de soporte para NIF y NIE; segundo apellido para NIF; NIF/NIE con letra de control válida.</li>
 *     <li>Dirección con código postal y país; código de municipio INE si reside en España, nombre del
 *     municipio en caso contrario.</li>
 *     <li>Al menos un teléfono o un correo.</li>
 *     <li>Parentesco para los menores.</li>
 * </ul>
 * Además se exigen sexo y nacionalidad (datos del anexo I del RD 933/2021) y que el titular sea mayor de edad.
 */
@Component
public class TravelerValidator {

    static final int ADULT_AGE = 18;
    private static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

    public List<Issue> validate(List<TravelerData> travelers, LocalDate arrival, int expectedCount) {
        List<Issue> issues = new ArrayList<>();
        if (travelers.size() != expectedCount) {
            issues.add(new Issue(-1, "travelers",
                    "Deben registrarse las " + expectedCount + " personas de la reserva (hay " + travelers.size() + ")"));
        }
        boolean anyAdult = false;
        for (int i = 0; i < travelers.size(); i++) {
            TravelerData t = travelers.get(i);
            boolean adult = validatePerson(i, t, arrival, issues);
            anyAdult |= adult;
            if (i == 0 && t.birthDate() != null && !adult) {
                issues.add(new Issue(0, "birthDate", "El titular de la reserva debe ser mayor de edad"));
            }
        }
        if (!travelers.isEmpty() && !anyAdult) {
            issues.add(new Issue(-1, "travelers", "Debe haber al menos un adulto"));
        }
        return issues;
    }

    /** @return si la persona es mayor de edad en la fecha de llegada */
    private boolean validatePerson(int i, TravelerData t, LocalDate arrival, List<Issue> issues) {
        boolean adult = false;
        if (t.birthDate() == null) {
            issues.add(new Issue(i, "birthDate", "La fecha de nacimiento es obligatoria"));
        } else {
            adult = Period.between(t.birthDate(), arrival).getYears() >= ADULT_AGE;
        }
        if (blank(t.sex())) {
            issues.add(new Issue(i, "sex", "El sexo es obligatorio"));
        }
        if (blank(t.nationality())) {
            issues.add(new Issue(i, "nationality", "La nacionalidad es obligatoria"));
        }

        boolean hasDocument = t.documentType() != null && !blank(t.documentNumber());
        if (t.documentType() != null && blank(t.documentNumber())) {
            issues.add(new Issue(i, "documentNumber", "Indique el número de documento"));
        } else if (t.documentType() == null && !blank(t.documentNumber())) {
            issues.add(new Issue(i, "documentType", "Indique el tipo de documento"));
        }
        if (adult && !hasDocument) {
            issues.add(new Issue(i, "documentNumber", "El documento de identidad es obligatorio para mayores de edad"));
        }
        if (hasDocument) {
            String number = t.documentNumber().trim().toUpperCase(Locale.ROOT);
            if (t.documentType() == DocumentType.NIF && !isValidDni(number)) {
                issues.add(new Issue(i, "documentNumber", "El NIF no es válido (compruebe la letra)"));
            }
            if (t.documentType() == DocumentType.NIE && !isValidNie(number)) {
                issues.add(new Issue(i, "documentNumber", "El NIE no es válido (compruebe la letra)"));
            }
            if ((t.documentType() == DocumentType.NIF || t.documentType() == DocumentType.NIE)
                    && blank(t.documentSupportNumber())) {
                issues.add(new Issue(i, "documentSupportNumber",
                        "El número de soporte es obligatorio para DNI y NIE (figura en el propio documento)"));
            }
            if (t.documentType() == DocumentType.NIF && blank(t.lastName2())) {
                issues.add(new Issue(i, "lastName2", "El segundo apellido es obligatorio con DNI"));
            }
        }

        if (blank(t.addressLine())) {
            issues.add(new Issue(i, "addressLine", "La dirección de residencia es obligatoria"));
        }
        if (blank(t.postalCode())) {
            issues.add(new Issue(i, "postalCode", "El código postal es obligatorio"));
        }
        if (blank(t.countryCode())) {
            issues.add(new Issue(i, "countryCode", "El país de residencia es obligatorio"));
        } else if ("ESP".equals(t.countryCode())) {
            if (blank(t.municipalityCode())) {
                issues.add(new Issue(i, "municipalityCode", "Indique el municipio de residencia (código INE)"));
            }
        } else if (blank(t.city())) {
            issues.add(new Issue(i, "city", "Indique la localidad de residencia"));
        }
        if (blank(t.phone()) && blank(t.phoneLandline()) && blank(t.email())) {
            issues.add(new Issue(i, "phone", "Indique al menos un teléfono o un correo electrónico"));
        }
        if (t.birthDate() != null && !adult && blank(t.relationship())) {
            issues.add(new Issue(i, "relationship", "Indique el parentesco del menor con el adulto responsable"));
        }
        return adult;
    }

    /** DNI: 8 dígitos + letra de control (módulo 23). */
    static boolean isValidDni(String value) {
        if (!value.matches("^[0-9]{8}[A-Z]$")) {
            return false;
        }
        return DNI_LETTERS.charAt(Integer.parseInt(value.substring(0, 8)) % 23) == value.charAt(8);
    }

    /** NIE: X/Y/Z + 7 dígitos + letra; X=0, Y=1, Z=2 para el cálculo. */
    static boolean isValidNie(String value) {
        if (!value.matches("^[XYZ][0-9]{7}[A-Z]$")) {
            return false;
        }
        String digits = "XYZ".indexOf(value.charAt(0)) + value.substring(1, 8);
        return DNI_LETTERS.charAt(Integer.parseInt(digits) % 23) == value.charAt(8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
