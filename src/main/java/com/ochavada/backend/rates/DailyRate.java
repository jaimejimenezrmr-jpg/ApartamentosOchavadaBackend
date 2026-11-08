package com.ochavada.backend.rates;

import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.rates.pricing.DayOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Ajuste puntual de un día: precio y restricciones que prevalecen sobre la temporada y el plan.
 * Un ajuste sin ningún valor no tiene sentido y se elimina.
 */
@Entity
@Table(name = "daily_rate")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRate extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_plan_id", nullable = false)
    private RatePlan ratePlan;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_stay")
    private Integer minStay;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_stay")
    private Integer maxStay;

    @Column(name = "closed_to_arrival", nullable = false)
    private boolean closedToArrival;

    @Column(name = "closed_to_departure", nullable = false)
    private boolean closedToDeparture;

    @Column(name = "stop_sell", nullable = false)
    private boolean stopSell;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    public DailyRate(RatePlan ratePlan, LocalDate stayDate) {
        this.ratePlan = ratePlan;
        this.stayDate = stayDate;
    }

    public boolean isEmpty() {
        return price == null && minStay == null && maxStay == null
                && !closedToArrival && !closedToDeparture && !stopSell;
    }

    public DayOverride toOverride() {
        return new DayOverride(stayDate, price, minStay, maxStay, closedToArrival, closedToDeparture, stopSell);
    }
}
