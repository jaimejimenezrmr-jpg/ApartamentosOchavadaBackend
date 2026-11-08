package com.ochavada.backend.availability.dto;

import com.ochavada.backend.catalog.dto.PublicRoomTypeSummary;
import com.ochavada.backend.rates.MealPlan;
import com.ochavada.backend.rates.pricing.RestrictionViolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de la búsqueda de disponibilidad de la web pública.
 */
public record AvailabilityResponse(
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        int adults,
        int children,
        String currency,
        List<RoomTypeAvailability> results) {

    /** Una tipología con unidades libres y sus tarifas para la estancia. */
    public record RoomTypeAvailability(
            PublicRoomTypeSummary roomType,
            int availableUnits,
            List<RateOffer> offers) {
    }

    /**
     * Tarifa presupuestada. Si {@code bookable} es {@code false}, {@code restrictions} explica por qué
     * (p. ej. estancia mínima) para que la web pueda sugerir otras fechas.
     */
    public record RateOffer(
            UUID ratePlanId,
            String code,
            String name,
            String description,
            MealPlan mealPlan,
            boolean refundable,
            LocalDate freeCancellationUntil,
            BigDecimal prepaymentPercent,
            BigDecimal prepaymentAmount,
            BigDecimal totalAmount,
            BigDecimal averageNightlyPrice,
            List<NightPrice> nights,
            boolean bookable,
            List<RestrictionViolation> restrictions) {
    }

    public record NightPrice(LocalDate date, BigDecimal price) {
    }
}
