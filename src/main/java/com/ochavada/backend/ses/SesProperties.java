package com.ochavada.backend.ses;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Servicio de Comunicación de SES.HOSPEDAJES ({@code app.ses.*}).
 *
 * @param enabled            {@code false}: las comunicaciones se generan y quedan pendientes, sin enviarse
 * @param endpoint           pruebas: {@code https://hospedajes.pre-ses.mir.es/hospedajes-web/ws/v1/comunicacion};
 *                           producción: {@code https://hospedajes.ses.mir.es/hospedajes-web/ws/v1/comunicacion}
 * @param username           usuario del servicio web (credenciales recibidas al registrarse)
 * @param password           contraseña del servicio web
 * @param landlordCode       código de arrendador (10 dígitos, con ceros a la izquierda)
 * @param establishmentCode  código del establecimiento (10 dígitos, con ceros a la izquierda)
 * @param application        nombre del sistema que envía (etiqueta {@code aplicacion}, máx. 50)
 * @param reportReservations comunicar también las reservas de hospedaje (RH), además de los partes (PV)
 * @param truststorePath     almacén con el certificado del servicio, si la JVM no confía en él por defecto
 * @param pvNamespace        espacio de nombres del XML de alta de partes de viajeros
 * @param rhNamespace        espacio de nombres del XML de alta de reservas de hospedaje
 * @param batchCheckDelay    espera entre consultas del estado de un lote enviado
 */
@Validated
@ConfigurationProperties("app.ses")
public record SesProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("https://hospedajes.pre-ses.mir.es/hospedajes-web/ws/v1/comunicacion") String endpoint,
        String username,
        String password,
        @Pattern(regexp = "^$|^[0-9]{10}$", message = "El código de arrendador tiene 10 dígitos") String landlordCode,
        @Pattern(regexp = "^$|^[0-9]{10}$", message = "El código de establecimiento tiene 10 dígitos") String establishmentCode,
        @NotBlank @Size(max = 50) @DefaultValue("Apartamentos Ochavada PMS") String application,
        @DefaultValue("true") boolean reportReservations,
        String truststorePath,
        String truststorePassword,
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @DefaultValue("30s") Duration readTimeout,
        @NotBlank @DefaultValue("http://www.neg.hospedajes.mir.es/altaParteHospedaje") String pvNamespace,
        @NotBlank @DefaultValue("http://www.neg.hospedajes.mir.es/altaReservaHospedaje") String rhNamespace,
        @NotNull @DefaultValue("2m") Duration batchCheckDelay) {

    public boolean isConfigured() {
        return enabled && notBlank(username) && notBlank(password) && notBlank(landlordCode) && notBlank(establishmentCode);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
