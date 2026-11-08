package com.ochavada.backend.reservation.backoffice;

import com.ochavada.backend.availability.StayQuoteService;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.BusinessRuleException;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.pricing.RestrictionViolation;
import com.ochavada.backend.rates.pricing.StayQuote;
import com.ochavada.backend.reservation.NightCharge;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Precio de una estancia introducida o modificada por recepción: con el motor de tarifas o con un
 * precio total pactado, repartido entre las noches (el céntimo sobrante va a la última).
 */
@Component
public class StayPricer {

    private final StayQuoteService quoteService;

    public StayPricer(StayQuoteService quoteService) {
        this.quoteService = quoteService;
    }

    public PricedStay price(RatePlan plan, LocalDate checkIn, LocalDate checkOut, BigDecimal totalOverride,
                            boolean overrideRestrictions) {
        List<RestrictionViolation> violations = List.of();
        if (plan != null) {
            StayQuote quote = quoteService.quote(List.of(plan), checkIn, checkOut).get(plan.getId());
            violations = quote.violations();
            if (!quote.bookable() && !overrideRestrictions) {
                throw new BusinessRuleException("reservation.rate_not_bookable",
                        "La tarifa no admite esta estancia. Puede forzarla indicando overrideRestrictions",
                        Map.of("restrictions", violations));
            }
            if (totalOverride == null) {
                List<NightCharge> nights = quote.nights().stream()
                        .map(n -> new NightCharge(n.date(), n.price(), plan.getId()))
                        .toList();
                return new PricedStay(quote.totalAmount(), nights, violations);
            }
        }
        if (totalOverride == null) {
            throw new BadRequestException("reservation.price_required",
                    "La reserva no tiene plan de tarifa: indique el precio total");
        }
        return new PricedStay(totalOverride.setScale(2, RoundingMode.HALF_UP),
                distribute(totalOverride, checkIn, checkOut, plan), violations);
    }

    /** Reparte un total entre las noches de la estancia (el céntimo sobrante va a la última). */
    public static List<NightCharge> distribute(BigDecimal total, LocalDate checkIn, LocalDate checkOut, RatePlan plan) {
        int nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);
        BigDecimal perNight = total.divide(BigDecimal.valueOf(nights), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(perNight.multiply(BigDecimal.valueOf(nights - 1L))).setScale(2, RoundingMode.HALF_UP);
        List<NightCharge> result = new ArrayList<>(nights);
        LocalDate date = checkIn;
        for (int i = 0; i < nights; i++, date = date.plusDays(1)) {
            result.add(new NightCharge(date, i == nights - 1 ? last : perNight, plan == null ? null : plan.getId()));
        }
        return result;
    }

    public record PricedStay(BigDecimal total, List<NightCharge> nights, List<RestrictionViolation> overridden) {
    }
}
