package com.ochavada.backend.reservation.backoffice.dto;

import com.ochavada.backend.payment.PaymentProvider;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Cuerpos de las acciones sobre reservas. */
public final class ActionRequests {

    private ActionRequests() {
    }

    public record RoomAssignment(@NotNull UUID roomId) {
    }

    public record Cancellation(@NotBlank @Size(max = 500) String reason) {
    }

    /** {@code force} permite continuar pese a la advertencia (unidad sin limpiar, saldo pendiente). */
    public record FrontDeskAction(Boolean force) {

        public boolean forced() {
            return Boolean.TRUE.equals(force);
        }
    }

    public record PropertyPayment(
            @NotNull PaymentProvider provider,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 100) String reference) {

        @AssertTrue(message = "Solo se registran cobros en efectivo, datáfono o transferencia")
        public boolean isProviderAllowed() {
            return provider == null || provider == PaymentProvider.CASH || provider == PaymentProvider.CARD_TERMINAL
                    || provider == PaymentProvider.BANK_TRANSFER;
        }
    }

    public record Refund(
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {
    }
}
