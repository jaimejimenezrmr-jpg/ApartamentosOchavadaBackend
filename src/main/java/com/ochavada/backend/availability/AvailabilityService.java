package com.ochavada.backend.availability;

import com.ochavada.backend.availability.dto.AvailabilityResponse;
import com.ochavada.backend.availability.dto.AvailabilityResponse.NightPrice;
import com.ochavada.backend.availability.dto.AvailabilityResponse.RateOffer;
import com.ochavada.backend.availability.dto.AvailabilityResponse.RoomTypeAvailability;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.catalog.dto.PublicRoomTypeSummary;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RatePlanRepository;
import com.ochavada.backend.rates.pricing.StayQuote;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buscador público: tipologías con capacidad para el grupo, con unidades libres toda la estancia y
 * con al menos un plan de tarifa activo, presupuestadas por el motor de precios.
 */
@Service
public class AvailabilityService {

    private static final Comparator<RateOffer> OFFER_ORDER = Comparator
            .comparing(RateOffer::bookable).reversed()
            .thenComparing(RateOffer::totalAmount);

    private final RoomTypeRepository roomTypeRepository;
    private final RatePlanRepository ratePlanRepository;
    private final InventoryRepository inventoryRepository;
    private final StayQuoteService quoteService;
    private final StayRequestValidator stayValidator;
    private final PropertyProperties property;

    public AvailabilityService(RoomTypeRepository roomTypeRepository, RatePlanRepository ratePlanRepository,
                               InventoryRepository inventoryRepository, StayQuoteService quoteService,
                               StayRequestValidator stayValidator, PropertyProperties property) {
        this.roomTypeRepository = roomTypeRepository;
        this.ratePlanRepository = ratePlanRepository;
        this.inventoryRepository = inventoryRepository;
        this.quoteService = quoteService;
        this.stayValidator = stayValidator;
        this.property = property;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse search(LocalDate checkIn, LocalDate checkOut, int adults, int children) {
        stayValidator.validate(checkIn, checkOut);
        int nights = (int) ChronoUnit.DAYS.between(checkIn, checkOut);

        List<RoomType> candidates = roomTypeRepository.findActiveAccommodating(adults, children);
        List<UUID> candidateIds = candidates.stream().map(RoomType::getId).toList();
        Map<UUID, Integer> units = inventoryRepository.availableUnitsForStay(candidateIds, checkIn, checkOut);

        List<RoomType> withUnits = candidates.stream().filter(rt -> units.getOrDefault(rt.getId(), 0) > 0).toList();
        Map<UUID, List<RatePlan>> plansByType = ratePlanRepository
                .findByRoomTypeIdInAndActiveTrue(withUnits.stream().map(RoomType::getId).toList()).stream()
                .collect(Collectors.groupingBy(p -> p.getRoomType().getId()));
        Map<UUID, StayQuote> quotes = quoteService.quote(
                plansByType.values().stream().flatMap(List::stream).toList(), checkIn, checkOut);

        List<RoomTypeAvailability> results = new ArrayList<>();
        for (RoomType roomType : withUnits) {
            List<RatePlan> plans = plansByType.getOrDefault(roomType.getId(), List.of());
            if (plans.isEmpty()) {
                continue;
            }
            List<RateOffer> offers = plans.stream()
                    .map(plan -> toOffer(plan, quotes.get(plan.getId())))
                    .sorted(OFFER_ORDER)
                    .toList();
            results.add(new RoomTypeAvailability(PublicRoomTypeSummary.from(roomType),
                    units.get(roomType.getId()), offers));
        }
        return new AvailabilityResponse(checkIn, checkOut, nights, adults, children, property.currency(), results);
    }

    private static RateOffer toOffer(RatePlan plan, StayQuote quote) {
        return new RateOffer(
                plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(), plan.getMealPlan(),
                plan.isRefundable(), quote.freeCancellationUntil(), plan.getPrepaymentPercent(),
                quote.prepaymentAmount(), quote.totalAmount(), quote.averageNightlyPrice(),
                quote.nights().stream().map(n -> new NightPrice(n.date(), n.price())).toList(),
                quote.bookable(), quote.violations());
    }
}
