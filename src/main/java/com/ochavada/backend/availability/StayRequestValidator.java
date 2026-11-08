package com.ochavada.backend.availability;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.config.PropertyProperties;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reglas comunes de fechas de estancia para el buscador y el checkout (deben coincidir: lo que se
 * puede buscar se puede reservar).
 */
@Component
public class StayRequestValidator {

    private final PropertyCalendar calendar;
    private final PropertyProperties property;

    public StayRequestValidator(PropertyCalendar calendar, PropertyProperties property) {
        this.calendar = calendar;
        this.property = property;
    }

    public void validate(LocalDate checkIn, LocalDate checkOut) {
        LocalDate today = calendar.today();
        if (!checkOut.isAfter(checkIn)) {
            throw new BadRequestException("availability.invalid_dates",
                    "La fecha de salida debe ser posterior a la de entrada");
        }
        if (checkIn.isBefore(today)) {
            throw new BadRequestException("availability.past_date", "La fecha de entrada no puede ser anterior a hoy");
        }
        if (checkIn.isAfter(today.plusDays(property.bookingHorizonDays()))) {
            throw new BadRequestException("availability.beyond_horizon",
                    "Solo se admiten reservas con hasta " + property.bookingHorizonDays() + " días de antelación");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > property.maxNightsPerStay()) {
            throw new BadRequestException("availability.stay_too_long",
                    "La estancia máxima reservable online es de " + property.maxNightsPerStay() + " noches",
                    Map.of("maxNights", property.maxNightsPerStay()));
        }
    }
}
