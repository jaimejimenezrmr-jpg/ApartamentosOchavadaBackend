package com.ochavada.backend.rates.dto;

import com.ochavada.backend.rates.MealPlan;
import com.ochavada.backend.rates.RatePlan;
import java.math.BigDecimal;
import java.util.UUID;

public record RatePlanResponse(
        UUID id,
        UUID roomTypeId,
        String roomTypeName,
        String code,
        String name,
        String description,
        MealPlan mealPlan,
        boolean refundable,
        Integer freeCancellationDays,
        BigDecimal prepaymentPercent,
        BigDecimal basePrice,
        int minStay,
        Integer maxStay,
        boolean defaultPlan,
        boolean active,
        String channelRateCode,
        long version) {

    public static RatePlanResponse from(RatePlan plan) {
        return new RatePlanResponse(plan.getId(), plan.getRoomType().getId(), plan.getRoomType().getName(),
                plan.getCode(), plan.getName(), plan.getDescription(), plan.getMealPlan(), plan.isRefundable(),
                plan.getFreeCancellationDays(), plan.getPrepaymentPercent(), plan.getBasePrice(), plan.getMinStay(),
                plan.getMaxStay(), plan.isDefaultPlan(), plan.isActive(), plan.getChannelRateCode(),
                plan.getVersion());
    }
}
