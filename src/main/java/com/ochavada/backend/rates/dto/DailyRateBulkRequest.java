package com.ochavada.backend.rates.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Edición masiva de ajustes diarios (el "pincel" del calendario de tarifas).
 * <p>
 * Semántica de cada campo: {@code null} = no se toca; un valor = se fija. Para eliminar un ajuste y
 * volver al valor de la temporada/plan se incluye el campo en {@code clear}. Los booleanos fijan el
 * valor indicado (incluido {@code false}).
 *
 * <pre>
 * { "firstNight": "2027-07-01", "lastNight": "2027-07-31", "daysOfWeek": ["FRIDAY", "SATURDAY"],
 *   "price": 180.00, "minStay": 2, "clear": ["MAX_STAY"] }
 * </pre>
 *
 * @param daysOfWeek si se indica, solo se aplica a esos días de la semana dentro del rango
 */
public record DailyRateBulkRequest(
        @NotNull LocalDate firstNight,
        @NotNull LocalDate lastNight,
        Set<DayOfWeek> daysOfWeek,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Min(1) @Max(365) Integer minStay,
        @Min(1) @Max(365) Integer maxStay,
        Boolean closedToArrival,
        Boolean closedToDeparture,
        Boolean stopSell,
        Set<ClearableField> clear) {

    public enum ClearableField {
        PRICE, MIN_STAY, MAX_STAY
    }

    public Set<ClearableField> clearOrEmpty() {
        return clear == null ? Set.of() : clear;
    }

    @AssertTrue(message = "La última noche no puede ser anterior a la primera")
    public boolean isRangeValid() {
        return firstNight == null || lastNight == null || !lastNight.isBefore(firstNight);
    }

    @AssertTrue(message = "Debe indicar al menos un cambio")
    public boolean isAnyChange() {
        return price != null || minStay != null || maxStay != null || closedToArrival != null
                || closedToDeparture != null || stopSell != null || !clearOrEmpty().isEmpty();
    }

    @AssertTrue(message = "No se puede fijar y eliminar el mismo campo a la vez")
    public boolean isClearConsistent() {
        Set<ClearableField> c = clearOrEmpty();
        return !(c.contains(ClearableField.PRICE) && price != null)
                && !(c.contains(ClearableField.MIN_STAY) && minStay != null)
                && !(c.contains(ClearableField.MAX_STAY) && maxStay != null);
    }

    @AssertTrue(message = "La estancia máxima no puede ser inferior a la mínima")
    public boolean isStayRangeValid() {
        return minStay == null || maxStay == null || maxStay >= minStay;
    }
}
