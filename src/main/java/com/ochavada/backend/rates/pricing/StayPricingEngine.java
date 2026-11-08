package com.ochavada.backend.rates.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Motor de precios y restricciones. Es puro (sin acceso a base de datos ni reloj): recibe el plan,
 * sus temporadas y sus ajustes diarios y calcula el precio por noche y la reservabilidad.
 *
 * <h2>Precedencia</h2>
 * Para cada noche: ajuste diario &gt; temporada (precio de fin de semana si la noche es de fin de semana
 * y la temporada lo define) &gt; precio base del plan. La misma precedencia se aplica a la estancia
 * mínima y máxima.
 *
 * <h2>Restricciones</h2>
 * <ul>
 *     <li>Stop-sell: en cualquier noche de la estancia.</li>
 *     <li>CTA, estancia mínima y máxima: según el día de llegada (convención de Booking.com y Beds24).</li>
 *     <li>CTD: según el día de salida.</li>
 * </ul>
 */
public class StayPricingEngine {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final Set<DayOfWeek> weekendNights;

    public StayPricingEngine(Set<DayOfWeek> weekendNights) {
        this.weekendNights = weekendNights.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekendNights);
    }

    public DayRate resolveDay(LocalDate date, RatePlanTerms plan, List<SeasonRate> seasons,
                              Map<LocalDate, DayOverride> overrides) {
        Optional<SeasonRate> season = seasons.stream().filter(s -> s.covers(date)).findFirst();
        DayOverride override = overrides.get(date);

        BigDecimal price;
        PriceSource source;
        if (override != null && override.price() != null) {
            price = override.price();
            source = PriceSource.DAILY_OVERRIDE;
        } else if (season.isPresent()) {
            SeasonRate s = season.get();
            if (s.weekendPrice() != null && weekendNights.contains(date.getDayOfWeek())) {
                price = s.weekendPrice();
                source = PriceSource.SEASON_WEEKEND;
            } else {
                price = s.nightlyPrice();
                source = PriceSource.SEASON;
            }
        } else {
            price = plan.basePrice();
            source = PriceSource.BASE;
        }

        int minStay = firstNonNull(
                override != null ? override.minStay() : null,
                season.map(SeasonRate::minStay).orElse(null),
                plan.minStay());
        Integer maxStay = firstNonNullOrNull(
                override != null ? override.maxStay() : null,
                season.map(SeasonRate::maxStay).orElse(null),
                plan.maxStay());

        return new DayRate(date, price.setScale(MONEY_SCALE, RoundingMode.HALF_UP), source, minStay, maxStay,
                override != null && override.closedToArrival(),
                override != null && override.closedToDeparture(),
                override != null && override.stopSell());
    }

    /**
     * @param today fecha actual en la zona horaria del alojamiento (para el plazo de cancelación)
     */
    public StayQuote quote(LocalDate checkIn, LocalDate checkOut, LocalDate today, RatePlanTerms plan,
                           List<SeasonRate> seasons, Map<LocalDate, DayOverride> overrides) {
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("La fecha de salida debe ser posterior a la de entrada");
        }
        List<DayRate> nights = checkIn.datesUntil(checkOut)
                .map(night -> resolveDay(night, plan, seasons, overrides))
                .toList();
        int nightCount = nights.size();

        BigDecimal total = nights.stream().map(DayRate::price).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal average = total.divide(BigDecimal.valueOf(nightCount), MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal prepayment = total.multiply(plan.prepaymentPercent())
                .divide(ONE_HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);

        List<RestrictionViolation> violations = new ArrayList<>();
        nights.stream().filter(DayRate::stopSell).findFirst().ifPresent(closed -> violations.add(
                new RestrictionViolation(RestrictionCode.STOP_SELL,
                        "No quedan unidades a la venta para la noche del " + closed.date(), null, closed.date())));

        DayRate arrival = nights.getFirst();
        if (arrival.closedToArrival()) {
            violations.add(new RestrictionViolation(RestrictionCode.CLOSED_TO_ARRIVAL,
                    "No se admiten llegadas el " + checkIn, null, checkIn));
        }
        DayRate departure = resolveDay(checkOut, plan, seasons, overrides);
        if (departure.closedToDeparture()) {
            violations.add(new RestrictionViolation(RestrictionCode.CLOSED_TO_DEPARTURE,
                    "No se admiten salidas el " + checkOut, null, checkOut));
        }
        if (nightCount < arrival.minStay()) {
            violations.add(new RestrictionViolation(RestrictionCode.MIN_STAY,
                    "La estancia mínima para llegadas el " + checkIn + " es de " + arrival.minStay() + " noches",
                    arrival.minStay(), checkIn));
        }
        if (arrival.maxStay() != null && nightCount > arrival.maxStay()) {
            violations.add(new RestrictionViolation(RestrictionCode.MAX_STAY,
                    "La estancia máxima para llegadas el " + checkIn + " es de " + arrival.maxStay() + " noches",
                    arrival.maxStay(), checkIn));
        }

        LocalDate freeCancellationUntil = null;
        if (plan.refundable() && plan.freeCancellationDays() != null) {
            LocalDate deadline = checkIn.minusDays(plan.freeCancellationDays());
            freeCancellationUntil = deadline.isBefore(today) ? null : deadline;
        }

        return new StayQuote(checkIn, checkOut, nights, total, average, prepayment, freeCancellationUntil,
                List.copyOf(violations));
    }

    private static int firstNonNull(Integer first, Integer second, int fallback) {
        if (first != null) {
            return first;
        }
        return second != null ? second : fallback;
    }

    private static Integer firstNonNullOrNull(Integer first, Integer second, Integer fallback) {
        if (first != null) {
            return first;
        }
        return second != null ? second : fallback;
    }
}
