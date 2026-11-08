package com.ochavada.backend.channel.beds24;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Channel manager Beds24, API V2 ({@code app.beds24.*}). Beds24 distribuye a Booking.com, Airbnb, Expedia y
 * Google Hotel Center; este sistema es la fuente de verdad del inventario y de las tarifas.
 *
 * @param enabled             {@code false}: no se sincroniza nada (útil hasta completar el mapeo)
 * @param baseUrl             URL base de la API V2
 * @param refreshToken        refresh token inicial (alternativa a canjear un código de invitación desde el backoffice)
 * @param webhookToken        secreto que Beds24 debe enviar en la cabecera {@code X-Ochavada-Webhook-Token}
 *                            (se configura como "custom header" del webhook de reservas en Beds24)
 * @param syncHorizonDays     días hacia delante que se envían en cada sincronización de inventario
 * @param otaCollectChannels  canales (nombre Beds24) que cobran al huésped: el resto se cobra en el alojamiento
 * @param pushBookings        enviar también a Beds24 las reservas propias (web, teléfono, mostrador)
 * @param pollOverlap         solapamiento del sondeo incremental de reservas modificadas
 */
@Validated
@ConfigurationProperties("app.beds24")
public record Beds24Properties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("https://api.beds24.com/v2") String baseUrl,
        String refreshToken,
        String webhookToken,
        @Min(1) @Max(730) @DefaultValue("365") int syncHorizonDays,
        @NotNull @DefaultValue({"airbnb", "expedia"}) Set<String> otaCollectChannels,
        @DefaultValue("true") boolean pushBookings,
        @NotNull @DefaultValue("2m") Duration pollOverlap,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("30s") Duration readTimeout) {
}
