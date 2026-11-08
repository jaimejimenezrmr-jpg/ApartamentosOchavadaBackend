package com.ochavada.backend.rates;

import com.ochavada.backend.common.domain.VersionedEntity;
import com.ochavada.backend.rates.pricing.SeasonRate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Temporada de un plan: precio por noche en {@code [startDate, endDate)}. En la API se expone como
 * primera y última noche (ambas incluidas), que es como lo piensa quien gestiona tarifas.
 */
@Entity
@Table(name = "rate_season")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RateSeason extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_plan_id", nullable = false)
    private RatePlan ratePlan;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "nightly_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal nightlyPrice;

    @Column(name = "weekend_price", precision = 12, scale = 2)
    private BigDecimal weekendPrice;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "min_stay")
    private Integer minStay;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_stay")
    private Integer maxStay;

    public RateSeason(RatePlan ratePlan) {
        this.ratePlan = ratePlan;
    }

    public LocalDate lastNight() {
        return endDate.minusDays(1);
    }

    public SeasonRate toSeasonRate() {
        return new SeasonRate(startDate, endDate, nightlyPrice, weekendPrice, minStay, maxStay);
    }
}
