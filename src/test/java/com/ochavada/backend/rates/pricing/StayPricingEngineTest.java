package com.ochavada.backend.rates.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StayPricingEngineTest {

    // 2027-07-05 es lunes; el viernes 9 y el sábado 10 son noches de fin de semana.
    private static final LocalDate MONDAY = LocalDate.of(2027, 7, 5);
    private static final LocalDate TODAY = LocalDate.of(2027, 6, 1);

    private final StayPricingEngine engine = new StayPricingEngine(Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));

    private final RatePlanTerms flexible = new RatePlanTerms(UUID.randomUUID(), money("100.00"), 1, null,
            money("30.00"), true, 7);

    @Test
    void basePriceAppliesWithoutSeasonsOrOverrides() {
        StayQuote quote = engine.quote(MONDAY, MONDAY.plusDays(3), TODAY, flexible, List.of(), Map.of());

        assertThat(quote.nightCount()).isEqualTo(3);
        assertThat(quote.nights()).extracting(DayRate::priceSource).containsOnly(PriceSource.BASE);
        assertThat(quote.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(quote.averageNightlyPrice()).isEqualByComparingTo("100.00");
        assertThat(quote.prepaymentAmount()).isEqualByComparingTo("90.00");
        assertThat(quote.bookable()).isTrue();
    }

    @Test
    void seasonAndWeekendPricesApplyPerNight() {
        SeasonRate summer = new SeasonRate(MONDAY, MONDAY.plusDays(30), money("150.00"), money("190.00"), null, null);

        // Jueves 8, viernes 9 y sábado 10: salida el domingo 11.
        StayQuote quote = engine.quote(MONDAY.plusDays(3), MONDAY.plusDays(6), TODAY, flexible, List.of(summer),
                Map.of());

        assertThat(quote.nights()).extracting(DayRate::priceSource)
                .containsExactly(PriceSource.SEASON, PriceSource.SEASON_WEEKEND, PriceSource.SEASON_WEEKEND);
        assertThat(quote.totalAmount()).isEqualByComparingTo("530.00");
    }

    @Test
    void dailyOverrideWinsOverSeason() {
        SeasonRate summer = new SeasonRate(MONDAY, MONDAY.plusDays(30), money("150.00"), null, null, null);
        LocalDate gala = MONDAY.plusDays(1);
        DayOverride override = new DayOverride(gala, money("250.00"), null, null, false, false, false);

        StayQuote quote = engine.quote(MONDAY, MONDAY.plusDays(2), TODAY, flexible, List.of(summer),
                Map.of(gala, override));

        assertThat(quote.nights()).extracting(DayRate::price)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(money("150.00"), money("250.00"));
        assertThat(quote.nights().get(1).priceSource()).isEqualTo(PriceSource.DAILY_OVERRIDE);
    }

    @Test
    void minStayIsEvaluatedOnArrivalDayWithPrecedence() {
        SeasonRate summer = new SeasonRate(MONDAY, MONDAY.plusDays(30), money("150.00"), null, 3, null);

        StayQuote twoNights = engine.quote(MONDAY, MONDAY.plusDays(2), TODAY, flexible, List.of(summer), Map.of());
        assertThat(twoNights.bookable()).isFalse();
        assertThat(twoNights.violations()).extracting(RestrictionViolation::code).containsExactly(RestrictionCode.MIN_STAY);
        assertThat(twoNights.violations().getFirst().value()).isEqualTo(3);

        // Un ajuste diario en el día de llegada rebaja la estancia mínima.
        DayOverride relax = new DayOverride(MONDAY, null, 2, null, false, false, false);
        StayQuote relaxed = engine.quote(MONDAY, MONDAY.plusDays(2), TODAY, flexible, List.of(summer),
                Map.of(MONDAY, relax));
        assertThat(relaxed.bookable()).isTrue();
        assertThat(relaxed.nights()).extracting(DayRate::priceSource).containsOnly(PriceSource.SEASON);
    }

    @Test
    void maxStayFromPlanIsEnforced() {
        RatePlanTerms shortStays = new RatePlanTerms(UUID.randomUUID(), money("80.00"), 1, 5, money("100.00"),
                false, null);

        StayQuote quote = engine.quote(MONDAY, MONDAY.plusDays(6), TODAY, shortStays, List.of(), Map.of());

        assertThat(quote.violations()).extracting(RestrictionViolation::code).containsExactly(RestrictionCode.MAX_STAY);
    }

    @Test
    void stopSellClosedToArrivalAndDepartureAreDetected() {
        LocalDate checkOut = MONDAY.plusDays(4);
        Map<LocalDate, DayOverride> overrides = Map.of(
                MONDAY, new DayOverride(MONDAY, null, null, null, true, false, false),
                MONDAY.plusDays(2), new DayOverride(MONDAY.plusDays(2), null, null, null, false, false, true),
                checkOut, new DayOverride(checkOut, null, null, null, false, true, false));

        StayQuote quote = engine.quote(MONDAY, checkOut, TODAY, flexible, List.of(), overrides);

        assertThat(quote.violations()).extracting(RestrictionViolation::code).containsExactlyInAnyOrder(
                RestrictionCode.STOP_SELL, RestrictionCode.CLOSED_TO_ARRIVAL, RestrictionCode.CLOSED_TO_DEPARTURE);
        assertThat(quote.violations()).filteredOn(v -> v.code() == RestrictionCode.STOP_SELL)
                .extracting(RestrictionViolation::date).containsExactly(MONDAY.plusDays(2));
    }

    @Test
    void closedToDepartureOnTheCheckoutDayOnlyAffectsThatDeparture() {
        // CTD el día 2 no impide una estancia que sale el día 3 aunque pase la noche del 2.
        LocalDate day2 = MONDAY.plusDays(2);
        Map<LocalDate, DayOverride> overrides = Map.of(day2, new DayOverride(day2, null, null, null, false, true, false));

        assertThat(engine.quote(MONDAY, MONDAY.plusDays(3), TODAY, flexible, List.of(), overrides).bookable()).isTrue();
        assertThat(engine.quote(MONDAY, day2, TODAY, flexible, List.of(), overrides).bookable()).isFalse();
    }

    @Test
    void freeCancellationDeadlineIsComputedAndHiddenWhenPassed() {
        StayQuote early = engine.quote(MONDAY, MONDAY.plusDays(2), TODAY, flexible, List.of(), Map.of());
        assertThat(early.freeCancellationUntil()).isEqualTo(MONDAY.minusDays(7));

        StayQuote lastMinute = engine.quote(MONDAY, MONDAY.plusDays(2), MONDAY.minusDays(3), flexible, List.of(),
                Map.of());
        assertThat(lastMinute.freeCancellationUntil()).isNull();

        RatePlanTerms nonRefundable = new RatePlanTerms(UUID.randomUUID(), money("90.00"), 1, null, money("100.00"),
                false, null);
        assertThat(engine.quote(MONDAY, MONDAY.plusDays(2), TODAY, nonRefundable, List.of(), Map.of())
                .freeCancellationUntil()).isNull();
    }

    @Test
    void prepaymentIsRoundedHalfUp() {
        RatePlanTerms oddPrepayment = new RatePlanTerms(UUID.randomUUID(), money("33.33"), 1, null,
                money("33.33"), true, 1);

        StayQuote quote = engine.quote(MONDAY, MONDAY.plusDays(1), TODAY, oddPrepayment, List.of(), Map.of());

        // 33.33 * 33.33 / 100 = 11.108889 -> 11.11
        assertThat(quote.prepaymentAmount()).isEqualByComparingTo("11.11");
    }

    @Test
    void invalidStayIsRejected() {
        assertThatThrownBy(() -> engine.quote(MONDAY, MONDAY, TODAY, flexible, List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
