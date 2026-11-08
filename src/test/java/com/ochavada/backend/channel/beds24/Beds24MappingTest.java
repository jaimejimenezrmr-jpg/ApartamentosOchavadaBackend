package com.ochavada.backend.channel.beds24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.pricing.DayRate;
import com.ochavada.backend.rates.pricing.PriceSource;
import com.ochavada.backend.reservation.ReservationChannel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Beds24MappingTest {

    private final RoomType roomType = new RoomType("DUP", "duplex", "Dúplex", "EUR");

    @Test
    void channelsAreMappedFromBeds24Names() {
        assertThat(Beds24BookingImporter.mapChannel("booking")).isEqualTo(ReservationChannel.BOOKING_COM);
        assertThat(Beds24BookingImporter.mapChannel("Booking.com")).isEqualTo(ReservationChannel.BOOKING_COM);
        assertThat(Beds24BookingImporter.mapChannel("airbnb")).isEqualTo(ReservationChannel.AIRBNB);
        assertThat(Beds24BookingImporter.mapChannel("expedia")).isEqualTo(ReservationChannel.EXPEDIA);
        assertThat(Beds24BookingImporter.mapChannel("Hotels.com")).isEqualTo(ReservationChannel.EXPEDIA);
        assertThat(Beds24BookingImporter.mapChannel("google")).isEqualTo(ReservationChannel.GOOGLE);
        assertThat(Beds24BookingImporter.mapChannel("vrbo")).isEqualTo(ReservationChannel.BEDS24_OTHER);
        assertThat(Beds24BookingImporter.mapChannel(null)).isEqualTo(ReservationChannel.BEDS24_OTHER);
    }

    @Test
    void countriesAreNormalisedToIso3() {
        assertThat(Beds24BookingImporter.iso3Country("es")).isEqualTo("ESP");
        assertThat(Beds24BookingImporter.iso3Country("GB")).isEqualTo("GBR");
        assertThat(Beds24BookingImporter.iso3Country("fra")).isEqualTo("FRA");
        assertThat(Beds24BookingImporter.iso3Country("Spain")).isNull();
        assertThat(Beds24BookingImporter.iso3Country(null)).isNull();
    }

    @Test
    void priceSlotsComeFromChannelRateCodesWithDefaultFallback() {
        RatePlan flexible = plan("FLEX", null, true);
        RatePlan nonRefundable = plan("NR", null, false);
        assertThat(Beds24InventorySync.slotAssignments(List.of(flexible, nonRefundable)))
                .containsExactly(Map.entry(1, flexible));

        nonRefundable.setChannelRateCode("PRICE3");
        flexible.setChannelRateCode("price17");
        Map<Integer, RatePlan> slots = Beds24InventorySync.slotAssignments(List.of(flexible, nonRefundable));
        assertThat(slots).containsExactly(Map.entry(3, nonRefundable));
        // Sin price1, las restricciones se toman del plan por defecto.
        assertThat(Beds24InventorySync.primaryPlan(slots, List.of(flexible, nonRefundable))).contains(flexible);
    }

    @Test
    void restrictionsUseBeds24Overrides() {
        assertThat(Beds24InventorySync.override(day(false, false, false))).isEqualTo("none");
        assertThat(Beds24InventorySync.override(day(true, false, false))).isEqualTo("noCheckIn");
        assertThat(Beds24InventorySync.override(day(false, true, false))).isEqualTo("noCheckOut");
        assertThat(Beds24InventorySync.override(day(true, true, false))).isEqualTo("noCheckInOrCheckOut");
        assertThat(Beds24InventorySync.override(day(true, true, true))).isEqualTo("blackout");
    }

    @Test
    void webhookBookingIdsAreExtractedFromAnyKnownShape() {
        Beds24WebhookService service = new Beds24WebhookService(null, null, null, JsonMapper.builder().build(), null,
                null);
        assertThat(service.extractBookingIds("{\"booking\":{\"id\":123}}", null)).containsExactly(123L);
        assertThat(service.extractBookingIds("{\"bookId\":\"456\"}", null)).containsExactly(456L);
        assertThat(service.extractBookingIds("[{\"id\":1},{\"bookid\":2}]", "3")).containsExactly(3L, 1L, 2L);
        assertThat(service.extractBookingIds("{\"other\":true}", "x")).isEmpty();
        assertThatThrownBy(() -> service.extractBookingIds("<xml/>", null)).isInstanceOf(BadRequestException.class);
    }

    private RatePlan plan(String code, String channelCode, boolean isDefault) {
        RatePlan plan = new RatePlan(roomType, code);
        plan.setChannelRateCode(channelCode);
        plan.setDefaultPlan(isDefault);
        plan.setBasePrice(BigDecimal.TEN);
        return plan;
    }

    private static DayRate day(boolean cta, boolean ctd, boolean stopSell) {
        return new DayRate(LocalDate.of(2026, 12, 1), BigDecimal.TEN, PriceSource.BASE, 1, null, cta, ctd, stopSell);
    }
}
