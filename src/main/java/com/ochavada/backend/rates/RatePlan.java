package com.ochavada.backend.rates;

import com.ochavada.backend.catalog.RoomType;
import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.rates.pricing.RatePlanTerms;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Plan de tarifa de una tipología (p. ej. "Flexible" y "No reembolsable"). Define el precio base,
 * las condiciones de cancelación y prepago y la estancia mínima/máxima por defecto.
 */
@Entity
@Table(name = "rate_plan")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RatePlan extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_plan", nullable = false, length = 20)
    private MealPlan mealPlan;

    @Column(name = "refundable", nullable = false)
    private boolean refundable;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "free_cancellation_days")
    private Integer freeCancellationDays;

    @Column(name = "prepayment_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal prepaymentPercent;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_stay", nullable = false)
    private int minStay;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_stay")
    private Integer maxStay;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPlan;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "channel_rate_code", length = 50)
    private String channelRateCode;

    public RatePlan(RoomType roomType, String code) {
        this.roomType = roomType;
        this.code = code;
        this.mealPlan = MealPlan.ROOM_ONLY;
        this.active = true;
        this.minStay = 1;
    }

    public RatePlanTerms terms() {
        return new RatePlanTerms(getId(), basePrice, minStay, maxStay, prepaymentPercent, refundable,
                freeCancellationDays);
    }
}
