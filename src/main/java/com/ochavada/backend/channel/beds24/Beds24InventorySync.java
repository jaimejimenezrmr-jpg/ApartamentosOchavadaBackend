package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.channel.beds24.Beds24Dtos.RoomCalendar;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.rates.DailyRate;
import com.ochavada.backend.rates.DailyRateRepository;
import com.ochavada.backend.rates.RatePlan;
import com.ochavada.backend.rates.RatePlanRepository;
import com.ochavada.backend.rates.RateSeason;
import com.ochavada.backend.rates.RateSeasonRepository;
import com.ochavada.backend.rates.pricing.DayOverride;
import com.ochavada.backend.rates.pricing.DayRate;
import com.ochavada.backend.rates.pricing.SeasonRate;
import com.ochavada.backend.rates.pricing.StayPricingEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Calcula y envía a Beds24 el inventario (ARI) de una tipología: unidades libres, precio por hueco de
 * tarifa y restricciones, día a día. Lee siempre el estado actual, de modo que reintentar o recibir los
 * eventos desordenados es inocuo.
 *
 * <h2>Vinculación de planes</h2>
 * El código de canal del plan ({@code channel_rate_code}) indica el hueco de precio de Beds24:
 * {@code price1} ... {@code price16}. Si ningún plan activo tiene hueco, el plan por defecto se envía como
 * {@code price1}. Las restricciones (estancia mínima/máxima, CTA, CTD, stop-sell) son por habitación en
 * Beds24: se toman del plan principal (el de {@code price1} o, en su defecto, el plan por defecto).
 */
@Slf4j
@Component
public class Beds24InventorySync {

    private static final Pattern PRICE_SLOT = Pattern.compile("^price([1-9]|1[0-6])$");

    private final RoomTypeRepository roomTypeRepository;
    private final RatePlanRepository ratePlanRepository;
    private final RateSeasonRepository seasonRepository;
    private final DailyRateRepository dailyRateRepository;
    private final InventoryRepository inventoryRepository;
    private final StayPricingEngine engine;
    private final Beds24Gateway gateway;
    private final Beds24Properties properties;
    private final PropertyCalendar calendar;

    public Beds24InventorySync(RoomTypeRepository roomTypeRepository, RatePlanRepository ratePlanRepository,
                               RateSeasonRepository seasonRepository, DailyRateRepository dailyRateRepository,
                               InventoryRepository inventoryRepository, StayPricingEngine engine,
                               Beds24Gateway gateway, Beds24Properties properties, PropertyCalendar calendar) {
        this.roomTypeRepository = roomTypeRepository;
        this.ratePlanRepository = ratePlanRepository;
        this.seasonRepository = seasonRepository;
        this.dailyRateRepository = dailyRateRepository;
        this.inventoryRepository = inventoryRepository;
        this.engine = engine;
        this.gateway = gateway;
        this.properties = properties;
        this.calendar = calendar;
    }

    /**
     * @param from primera noche ({@code null} = hoy)
     * @param to   noche siguiente a la última, exclusiva ({@code null} = fin del horizonte)
     * @return días enviados (0 si la tipología no está vinculada o el rango queda fuera del horizonte)
     */
    public int sync(UUID roomTypeId, LocalDate from, LocalDate to) {
        Optional<RoomType> found = roomTypeRepository.findById(roomTypeId);
        if (found.isEmpty() || found.get().getBeds24RoomId() == null) {
            return 0;
        }
        RoomType roomType = found.get();
        LocalDate today = calendar.today();
        LocalDate horizonEnd = today.plusDays(properties.syncHorizonDays());
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        LocalDate end = to == null || to.isAfter(horizonEnd) ? horizonEnd : to;
        if (!end.isAfter(start)) {
            return 0;
        }
        List<Map<String, Object>> days = buildCalendar(roomType, start, end);
        gateway.updateCalendar(new RoomCalendar(roomType.getBeds24RoomId(), days));
        log.debug("Beds24: inventario de {} enviado ({} → {}, {} días)", roomType.getCode(), start, end, days.size());
        return days.size();
    }

    List<Map<String, Object>> buildCalendar(RoomType roomType, LocalDate start, LocalDate end) {
        List<RatePlan> plans = ratePlanRepository.findByRoomTypeIdInAndActiveTrue(List.of(roomType.getId()));
        Map<Integer, RatePlan> slots = slotAssignments(plans);
        RatePlan primary = primaryPlan(slots, plans).orElse(null);

        List<UUID> planIds = new ArrayList<>(slots.values().stream().map(RatePlan::getId).distinct().toList());
        if (primary != null && !planIds.contains(primary.getId())) {
            planIds.add(primary.getId());
        }
        Map<UUID, List<SeasonRate>> seasons = planIds.isEmpty() ? Map.of()
                : seasonRepository.findOverlapping(planIds, start, end).stream().collect(Collectors.groupingBy(
                        s -> s.getRatePlan().getId(), Collectors.mapping(RateSeason::toSeasonRate, Collectors.toList())));
        Map<UUID, Map<LocalDate, DayOverride>> overrides = planIds.isEmpty() ? Map.of()
                : dailyRateRepository.findInRange(planIds, start, end).stream().collect(Collectors.groupingBy(
                        d -> d.getRatePlan().getId(), Collectors.toMap(DailyRate::getStayDate, DailyRate::toOverride)));
        Map<LocalDate, Integer> availability = inventoryRepository.nightlyAvailability(roomType.getId(), start, end);

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("from", date.toString());
            day.put("to", date.toString());
            day.put("numAvail", roomType.isActive() ? availability.getOrDefault(date, 0) : 0);
            if (primary != null) {
                DayRate rate = resolve(primary, date, seasons, overrides);
                day.put("minStay", rate.minStay());
                // 0 = sin estancia máxima.
                day.put("maxStay", rate.maxStay() == null ? 0 : rate.maxStay());
                day.put("override", override(rate));
            }
            for (Map.Entry<Integer, RatePlan> slot : slots.entrySet()) {
                day.put("price" + slot.getKey(), resolve(slot.getValue(), date, seasons, overrides).price());
            }
            days.add(day);
        }
        return days;
    }

    /** Hueco de precio de cada plan; sin vínculos explícitos, el plan por defecto ocupa {@code price1}. */
    static Map<Integer, RatePlan> slotAssignments(List<RatePlan> activePlans) {
        Map<Integer, RatePlan> slots = new TreeMap<>();
        activePlans.stream()
                .filter(p -> p.getChannelRateCode() != null)
                .sorted(Comparator.comparing(RatePlan::getCode))
                .forEach(p -> {
                    Matcher m = PRICE_SLOT.matcher(p.getChannelRateCode().trim().toLowerCase(Locale.ROOT));
                    if (m.matches()) {
                        slots.putIfAbsent(Integer.parseInt(m.group(1)), p);
                    }
                });
        if (slots.isEmpty()) {
            defaultPlan(activePlans).ifPresent(p -> slots.put(1, p));
        }
        return slots;
    }

    /** Plan cuyas restricciones se envían: el de {@code price1}, el plan por defecto o el primer vinculado. */
    static Optional<RatePlan> primaryPlan(Map<Integer, RatePlan> slots, List<RatePlan> activePlans) {
        if (slots.containsKey(1)) {
            return Optional.of(slots.get(1));
        }
        return activePlans.stream().filter(RatePlan::isDefaultPlan).findFirst()
                .or(() -> slots.values().stream().findFirst());
    }

    private static Optional<RatePlan> defaultPlan(List<RatePlan> activePlans) {
        return activePlans.stream().filter(RatePlan::isDefaultPlan).findFirst()
                .or(() -> activePlans.stream().min(Comparator.comparing(RatePlan::getCode)));
    }

    private DayRate resolve(RatePlan plan, LocalDate date, Map<UUID, List<SeasonRate>> seasons,
                            Map<UUID, Map<LocalDate, DayOverride>> overrides) {
        return engine.resolveDay(date, plan.terms(), seasons.getOrDefault(plan.getId(), List.of()),
                overrides.getOrDefault(plan.getId(), Map.of()));
    }

    /** Restricciones del día en el vocabulario de Beds24. */
    static String override(DayRate rate) {
        if (rate.stopSell()) {
            return "blackout";
        }
        if (rate.closedToArrival() && rate.closedToDeparture()) {
            return "noCheckInOrCheckOut";
        }
        if (rate.closedToArrival()) {
            return "noCheckIn";
        }
        if (rate.closedToDeparture()) {
            return "noCheckOut";
        }
        return "none";
    }
}
