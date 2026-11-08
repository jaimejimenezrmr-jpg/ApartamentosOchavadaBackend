package com.ochavada.backend.reservation.backoffice.dto;

import com.ochavada.backend.reservation.PaymentMode;
import com.ochavada.backend.reservation.ReservationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Reserva introducida por recepción. El precio sale del plan de tarifa salvo que se indique
 * {@code totalAmountOverride} (precio pactado); las restricciones de tarifa pueden saltarse con
 * {@code overrideRestrictions}. El titular es un huésped existente ({@code guestId}) o uno nuevo ({@code guest}).
 */
public record ManualReservationRequest(
        @NotNull UUID roomTypeId,
        UUID ratePlanId,
        UUID roomId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Min(1) @Max(20) Integer adults,
        @NotNull @Min(0) @Max(20) Integer children,
        @NotNull ReservationChannel channel,
        @Size(max = 100) String channelReference,
        UUID guestId,
        @Valid NewGuest guest,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal totalAmountOverride,
        PaymentMode paymentMode,
        @Size(max = 2000) String specialRequests,
        @Size(max = 5000) String internalNotes,
        LocalTime expectedArrivalTime,
        @Pattern(regexp = "es|en") String locale,
        Boolean overrideRestrictions,
        Boolean sendConfirmation) {

    public record NewGuest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName1,
            @Size(max = 100) String lastName2,
            @Email @Size(max = 254) String email,
            @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,30}$", message = "Teléfono no válido") String phone,
            @Pattern(regexp = "^[A-Z]{3}$", message = "País ISO 3166-1 alfa-3") String countryCode) {
    }

    @AssertTrue(message = "Indique un huésped existente (guestId) o los datos de uno nuevo (guest), no ambos")
    public boolean isHolderSpecified() {
        return (guestId == null) != (guest == null);
    }

    @AssertTrue(message = "Indique un plan de tarifa o un precio total pactado")
    public boolean isPriced() {
        return ratePlanId != null || totalAmountOverride != null;
    }

    @AssertTrue(message = "El canal DIRECT está reservado a la web propia")
    public boolean isChannelAllowed() {
        return channel != ReservationChannel.DIRECT;
    }

    @AssertTrue(message = "La fecha de salida debe ser posterior a la de entrada")
    public boolean isDateRangeValid() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }

    @AssertTrue(message = "Las reservas manuales se cobran en el alojamiento o las cobra la OTA")
    public boolean isPaymentModeAllowed() {
        return paymentMode == null || paymentMode != PaymentMode.ONLINE;
    }
}
