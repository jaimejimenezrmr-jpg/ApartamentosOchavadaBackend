package com.ochavada.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Parámetros del establecimiento ({@code app.property.*}).
 *
 * @param name                nombre comercial
 * @param timeZone            zona horaria del alojamiento: define "hoy" y las horas de check-in/out
 * @param currency            moneda ISO 4217 de tarifas y cobros
 * @param maxNightsPerStay    estancia máxima reservable online
 * @param bookingHorizonDays  con cuántos días de antelación máxima se puede reservar
 * @param weekendNights       noches a las que aplica el precio de fin de semana de las temporadas
 * @param checkInTime         hora local a partir de la que se puede entrar el día de llegada
 * @param checkOutTime        hora local límite de salida
 * @param address             dirección postal (emails, ficha de Google)
 * @param phone               teléfono de contacto para huéspedes
 * @param email               email de contacto para huéspedes
 */
@Validated
@ConfigurationProperties("app.property")
public record PropertyProperties(
        @NotBlank @DefaultValue("Apartamentos Ochavada") String name,
        @NotNull @DefaultValue("Europe/Madrid") ZoneId timeZone,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") @DefaultValue("EUR") String currency,
        @Min(1) @Max(365) @DefaultValue("30") int maxNightsPerStay,
        @Min(1) @Max(1095) @DefaultValue("540") int bookingHorizonDays,
        @NotEmpty @DefaultValue({"FRIDAY", "SATURDAY"}) Set<DayOfWeek> weekendNights,
        @NotNull @DefaultValue("15:00") LocalTime checkInTime,
        @NotNull @DefaultValue("11:00") LocalTime checkOutTime,
        String address,
        String phone,
        String email) {
}
