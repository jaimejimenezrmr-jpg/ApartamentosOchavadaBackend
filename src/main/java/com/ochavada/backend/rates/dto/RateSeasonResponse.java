package com.ochavada.backend.rates.dto;

import com.ochavada.backend.rates.RateSeason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RateSeasonResponse(
        UUID id,
        UUID ratePlanId,
        String name,
        LocalDate firstNight,
        LocalDate lastNight,
        BigDecimal nightlyPrice,
        BigDecimal weekendPrice,
        Integer minStay,
        Integer maxStay,
        long version) {

    public static RateSeasonResponse from(RateSeason season) {
        return new RateSeasonResponse(season.getId(), season.getRatePlan().getId(), season.getName(),
                season.getStartDate(), season.lastNight(), season.getNightlyPrice(), season.getWeekendPrice(),
                season.getMinStay(), season.getMaxStay(), season.getVersion());
    }
}
