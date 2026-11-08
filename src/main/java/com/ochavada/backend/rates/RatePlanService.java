package com.ochavada.backend.rates;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.catalog.RoomTypeRepository;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.rates.dto.RatePlanRequest;
import com.ochavada.backend.rates.dto.RatePlanResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RatePlanService {

    private final RatePlanRepository repository;
    private final RoomTypeRepository roomTypeRepository;

    private final ApplicationEventPublisher events;

    public RatePlanService(RatePlanRepository repository, RoomTypeRepository roomTypeRepository,
                           ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.roomTypeRepository = roomTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<RatePlanResponse> list(UUID roomTypeId) {
        List<RatePlan> plans = roomTypeId == null
                ? repository.findAllByOrderByRoomTypeDisplayOrderAscNameAsc()
                : repository.findByRoomTypeIdOrderByNameAsc(roomTypeId);
        return plans.stream().map(RatePlanResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RatePlanResponse get(UUID id) {
        return RatePlanResponse.from(find(id));
    }

    @Transactional
    public RatePlanResponse create(RatePlanRequest request) {
        RoomType roomType = roomTypeRepository.findById(request.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("tipología", request.roomTypeId()));
        if (repository.existsByRoomTypeIdAndCode(roomType.getId(), request.code())) {
            throw duplicateCode(request.code());
        }
        // El primer plan de una tipología es siempre el predeterminado.
        boolean makeDefault = request.defaultPlan() || !repository.existsByRoomTypeId(roomType.getId());
        RatePlan plan = new RatePlan(roomType, request.code());
        apply(plan, request);
        plan.setDefaultPlan(false);
        repository.saveAndFlush(plan);
        if (makeDefault) {
            markDefault(plan);
        }
        events.publishEvent(InventoryChangedEvent.fullHorizon(roomType.getId()));
        return RatePlanResponse.from(plan);
    }

    @Transactional
    public RatePlanResponse update(UUID id, RatePlanRequest request) {
        RatePlan plan = find(id);
        plan.assertVersion(request.version());
        if (!plan.getRoomType().getId().equals(request.roomTypeId())) {
            throw new BadRequestException("rate_plan.room_type_immutable",
                    "Un plan de tarifa no puede cambiar de tipología; cree uno nuevo");
        }
        if (repository.existsByRoomTypeIdAndCodeAndIdNot(request.roomTypeId(), request.code(), id)) {
            throw duplicateCode(request.code());
        }
        boolean wasDefault = plan.isDefaultPlan();
        if (wasDefault && !request.defaultPlan()) {
            throw new BadRequestException("rate_plan.default_required",
                    "Cada tipología necesita un plan predeterminado: marque otro como predeterminado en su lugar");
        }
        plan.setCode(request.code());
        apply(plan, request);
        plan.setDefaultPlan(wasDefault);
        repository.saveAndFlush(plan);
        if (!wasDefault && request.defaultPlan()) {
            markDefault(plan);
        }
        events.publishEvent(InventoryChangedEvent.fullHorizon(plan.getRoomType().getId()));
        return RatePlanResponse.from(plan);
    }

    @Transactional
    public void delete(UUID id) {
        RatePlan plan = find(id);
        if (plan.isDefaultPlan() && repository.findByRoomTypeIdOrderByNameAsc(plan.getRoomType().getId()).size() > 1) {
            throw new ConflictException("rate_plan.default_in_use",
                    "No se puede eliminar el plan predeterminado mientras existan otros; marque otro como predeterminado");
        }
        UUID roomTypeId = plan.getRoomType().getId();
        repository.delete(plan);
        repository.flush();
        events.publishEvent(InventoryChangedEvent.fullHorizon(roomTypeId));
    }

    RatePlan find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("plan de tarifa", id));
    }

    private void markDefault(RatePlan plan) {
        repository.clearDefaultExcept(plan.getRoomType().getId(), plan.getId());
        plan.setDefaultPlan(true);
        repository.saveAndFlush(plan);
    }

    private static void apply(RatePlan plan, RatePlanRequest r) {
        plan.setName(r.name().trim());
        plan.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        plan.setMealPlan(r.mealPlan());
        plan.setRefundable(r.refundable());
        // La base de datos exige que una tarifa no reembolsable no tenga plazo de cancelación.
        plan.setFreeCancellationDays(r.refundable() ? r.freeCancellationDays() : null);
        plan.setPrepaymentPercent(r.prepaymentPercent());
        plan.setBasePrice(r.basePrice());
        plan.setMinStay(r.minStay());
        plan.setMaxStay(r.maxStay());
        plan.setActive(r.active());
        plan.setChannelRateCode(r.channelRateCode() == null || r.channelRateCode().isBlank()
                ? null : r.channelRateCode().trim());
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("rate_plan.duplicate_code",
                "La tipología ya tiene un plan de tarifa con el código " + code);
    }
}
