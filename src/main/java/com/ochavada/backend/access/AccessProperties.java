package com.ochavada.backend.access;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Reglas de acceso de huéspedes ({@code app.access.*}).
 *
 * @param earlyAccess              cuánto antes de la hora de check-in se puede entrar el día de llegada
 * @param lateGrace                margen tras la hora de check-out en que las puertas siguen abriendo
 * @param selfCheckInRequiresPayment  el autocheck-in exige que no quede saldo pendiente
 * @param selfCheckInRequiresCleanRoom el autocheck-in exige que la unidad figure como limpia/inspeccionada
 * @param selfCheckInRequiresOnlineCheckin el autocheck-in exige haber registrado a los viajeros (parte SES)
 * @param lowBatteryThreshold      porcentaje de batería a partir del cual se avisa
 */
@Validated
@ConfigurationProperties("app.access")
public record AccessProperties(
        @NotNull @DefaultValue("0m") Duration earlyAccess,
        @NotNull @DefaultValue("30m") Duration lateGrace,
        @DefaultValue("true") boolean selfCheckInRequiresPayment,
        @DefaultValue("true") boolean selfCheckInRequiresCleanRoom,
        @DefaultValue("true") boolean selfCheckInRequiresOnlineCheckin,
        @Min(1) @Max(100) @DefaultValue("20") int lowBatteryThreshold) {
}
