package com.ochavada.backend.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import com.ochavada.backend.checkin.dto.TravelerData;
import com.ochavada.backend.checkin.dto.TravelersDtos.Issue;
import com.ochavada.backend.guest.DocumentType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelerValidatorTest {

    private static final LocalDate ARRIVAL = LocalDate.of(2027, 7, 10);

    private final TravelerValidator validator = new TravelerValidator();

    @Test
    void validatesSpanishDniAndNieCheckLetters() {
        assertThat(TravelerValidator.isValidDni("12345678Z")).isTrue();
        assertThat(TravelerValidator.isValidDni("12345678A")).isFalse();
        assertThat(TravelerValidator.isValidDni("1234567Z")).isFalse();
        // X1234567L: 01234567 % 23 = 19 → L
        assertThat(TravelerValidator.isValidNie("X1234567L")).isTrue();
        assertThat(TravelerValidator.isValidNie("X1234567A")).isFalse();
    }

    @Test
    void completeFamilyIsValid() {
        assertThat(validator.validate(List.of(spanishAdult(), minor("HJ")), ARRIVAL, 2)).isEmpty();
    }

    @Test
    void reportsEverySesRule() {
        TravelerData adult = new TravelerData("Ana", "Ruiz", null, "M", LocalDate.of(1990, 1, 1), "ESP",
                DocumentType.NIF, "12345678A", null, null, null, null, null, null, null, null, null, null, null,
                "ESP", null);

        List<Issue> issues = validator.validate(List.of(adult, minor(null)), ARRIVAL, 3);

        assertThat(issues).extracting(Issue::field).contains(
                "travelers",                 // faltan personas
                "documentNumber",            // letra del NIF incorrecta
                "documentSupportNumber",     // soporte obligatorio con DNI
                "lastName2",                 // segundo apellido obligatorio con DNI
                "addressLine", "postalCode", "municipalityCode",
                "phone",                     // sin contacto
                "relationship");             // menor sin parentesco
    }

    @Test
    void foreignResidentNeedsCityAndHolderMustBeAdult() {
        TravelerData foreigner = new TravelerData("Marie", "Dupont", null, "M", LocalDate.of(1985, 3, 3), "FRA",
                DocumentType.PAS, "AB123456", null, null, "marie@example.fr", null, null, "1 rue de la Paix", null,
                "75002", null, null, null, "FRA", null);
        assertThat(validator.validate(List.of(foreigner), ARRIVAL, 1)).extracting(Issue::field).containsExactly("city");

        List<Issue> minorHolder = validator.validate(List.of(minor("HJ")), ARRIVAL, 1);
        assertThat(minorHolder).extracting(Issue::message).anyMatch(m -> m.contains("titular"));
    }

    static TravelerData spanishAdult() {
        return new TravelerData("Lucía", "García", "López", "M", LocalDate.of(1988, 5, 4), "ESP", DocumentType.NIF,
                "12345678Z", "BAA000589", null, "lucia@example.com", "+34 600 111 222", null, "Calle Mayor 1",
                "2º B", "14920", "Aguilar de la Frontera", "14002", "Córdoba", "ESP", null);
    }

    static TravelerData minor(String relationship) {
        return new TravelerData("Pablo", "García", "López", "H", LocalDate.of(2018, 2, 2), "ESP", null, null, null,
                null, "lucia@example.com", null, null, "Calle Mayor 1", null, "14920", "Aguilar de la Frontera", "14002", "Córdoba",
                "ESP", relationship);
    }
}
