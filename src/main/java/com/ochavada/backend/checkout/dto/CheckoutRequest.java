package com.ochavada.backend.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Paso 1 del checkout: estancia elegida en el buscador + datos del titular. El precio NO viaja en la
 * petición: el servidor lo recalcula siempre.
 */
public record CheckoutRequest(
        @NotNull UUID ratePlanId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Min(1) @Max(20) Integer adults,
        @NotNull @Min(0) @Max(20) Integer children,
        @NotNull @Valid GuestDetails guest,
        @Size(max = 2000) String specialRequests,
        LocalTime expectedArrivalTime,
        @Pattern(regexp = "es|en", message = "Idioma no soportado") String locale,
        @NotNull @AssertTrue(message = "Debe aceptar las condiciones de reserva") Boolean acceptTerms,
        @NotNull @AssertTrue(message = "Debe aceptar la política de privacidad") Boolean acceptPrivacy,
        Boolean marketingConsent) {

    public record GuestDetails(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName1,
            @Size(max = 100) String lastName2,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido")
            String phone,
            @Pattern(regexp = "^[A-Z]{3}$", message = "Código de país ISO 3166-1 alfa-3 (p. ej. ESP)")
            String countryCode) {

        public GuestDetails {
            email = email == null ? null : email.strip();
        }
    }

    public String localeOrDefault() {
        return locale == null ? "es" : locale;
    }

    @AssertTrue(message = "La fecha de salida debe ser posterior a la de entrada")
    public boolean isDateRangeValid() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }
}
