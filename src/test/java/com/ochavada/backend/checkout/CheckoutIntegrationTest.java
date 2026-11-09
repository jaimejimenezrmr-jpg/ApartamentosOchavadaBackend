package com.ochavada.backend.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ochavada.backend.availability.InventoryRepository;
import com.ochavada.backend.integration.outbox.OutboxProcessor;
import com.ochavada.backend.notification.MailMessage;
import com.ochavada.backend.payment.gateway.PaymentGateway.IntentStatus;
import com.ochavada.backend.support.AbstractApiTest;
import com.ochavada.backend.support.CapturingMailGateway;
import com.ochavada.backend.support.FakePaymentGateway;
import com.ochavada.backend.support.StripeWebhookSigner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CheckoutIntegrationTest extends AbstractApiTest {

    private static final Pattern ACCESS_LINK = Pattern.compile("#acceso=([A-Za-z0-9_-]+)");

    @Autowired
    private FakePaymentGateway gateway;
    @Autowired
    private CapturingMailGateway mail;
    @Autowired
    private OutboxProcessor outbox;
    @Autowired
    private HoldExpiryService holdExpiry;
    @Autowired
    private InventoryRepository inventory;

    private UUID roomTypeId;
    private UUID flexPlanId;
    private UUID payAtPropertyPlanId;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private String email;

    @BeforeEach
    void seed() {
        String s = suffix();
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, max_children, base_price)
                values (?, ?, ?, 4, 4, 2, 100.00) returning id
                """, UUID.class, "CK-" + s, "checkout-" + s.toLowerCase(), "Checkout " + s);
        jdbc.update("insert into room (room_type_id, number) values (?, ?)", roomTypeId, "C" + s.substring(0, 6));
        // 30 % de prepago, cancelación gratuita hasta 7 días antes.
        flexPlanId = jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay, is_default)
                values (?, 'FLEX', 'Flexible', true, 7, 30.00, 120.00, 1, true) returning id
                """, UUID.class, roomTypeId);
        payAtPropertyPlanId = jdbc.queryForObject("""
                insert into rate_plan (room_type_id, code, name, refundable, free_cancellation_days,
                                       prepayment_percent, base_price, min_stay)
                values (?, 'PAP', 'Pago en el alojamiento', true, 2, 0.00, 130.00, 1) returning id
                """, UUID.class, roomTypeId);
        checkIn = mondayAfter(45);
        checkOut = checkIn.plusDays(3);
        email = "huesped-" + s.toLowerCase() + "@example.com";
    }

    @Test
    void fullOnlineBookingFlowFromHoldToGuestPortal() throws Exception {
        // ---------- Paso 1: retención + PaymentIntent
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/public/checkout/OCH-")))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(360.0))
                .andExpect(jsonPath("$.prepaymentAmount").value(108.0))
                .andExpect(jsonPath("$.amountDueAtProperty").value(252.0))
                .andExpect(jsonPath("$.freeCancellationUntil").value(checkIn.minusDays(7).toString()))
                .andExpect(jsonPath("$.stay.nights").value(3))
                .andExpect(jsonPath("$.payment.provider").value("STRIPE"))
                .andExpect(jsonPath("$.payment.publishableKey").value("pk_test_dummy"))
                .andExpect(jsonPath("$.holdExpiresAt").value(notNullValue()));
        String code = read(started, "$.reservationCode");
        String token = read(started, "$.checkoutToken");
        String clientSecret = read(started, "$.payment.clientSecret");
        FakePaymentGateway.Intent intent = gateway.intentBySecret(clientSecret);
        assertThat(intent.amount()).isEqualByComparingTo("108.00");
        assertThat(intent.receiptEmail()).isEqualTo(email);

        // La retención ocupa la única unidad.
        assertThat(units()).isZero();

        // Estado: requiere el secreto del checkout.
        checkoutStatus(code, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        checkoutStatus(code, "otro-token").andExpect(status().isNotFound());

        // ---------- Paso 2: Stripe confirma el cobro por webhook (firma real verificada)
        String event = StripeWebhookSigner.paymentIntentEvent("payment_intent.succeeded", intent.id(), 10800,
                "succeeded", null);
        webhook(event).andExpect(status().isOk());
        checkoutStatus(code, token).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.paidAmount").value(108.0))
                .andExpect(jsonPath("$.holdExpiresAt").value(nullValue()));

        // Stripe reenvía el mismo evento: idempotente.
        webhook(event).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select paid_amount from reservation where code = ?", BigDecimal.class, code))
                .isEqualByComparingTo("108.00");

        // ---------- Email de confirmación con enlace mágico (outbox)
        outbox.processDue(100);
        List<MailMessage> mails = mail.sentTo(email);
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().subject()).contains(code);
        assertThat(mails.getFirst().htmlBody()).contains("Acceder a mi estancia").doesNotContain("<script");
        Matcher link = ACCESS_LINK.matcher(mails.getFirst().textBody());
        assertThat(link.find()).isTrue();
        String accessToken = link.group(1);
        // El valor en claro del enlace nunca se guarda: solo su hash.
        assertThat(jdbc.queryForObject("select count(*) from guest_access_token where token_hash = ?", Integer.class,
                accessToken)).isZero();

        // ---------- Portal del huésped
        String guestJwt = read(mvc.perform(post("/api/v1/guest/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + accessToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationCode").value(code))
                .andExpect(jsonPath("$.tokenType").value("Bearer")), "$.accessToken");
        mvc.perform(get("/api/v1/guest/stay").header(HttpHeaders.AUTHORIZATION, "Bearer " + guestJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationCode").value(code))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.pendingAmount").value(252.0))
                .andExpect(jsonPath("$.roomNumber").value(nullValue()))
                .andExpect(jsonPath("$.checkInTime").value("15:00:00"));

        // Revocar el enlace corta el acceso aunque el JWT siga vigente.
        jdbc.update("update guest_access_token set revoked_at = now() where reservation_id = "
                + "(select id from reservation where code = ?)", code);
        mvc.perform(get("/api/v1/guest/stay").header(HttpHeaders.AUTHORIZATION, "Bearer " + guestJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("guest.invalid_access"));

        // Rastro de auditoría completo.
        List<String> actions = jdbc.queryForList("""
                select action from audit_log where entity_type = 'reservation'
                   and entity_id = (select id::text from reservation where code = ?) order by id
                """, String.class, code);
        assertThat(actions).containsSubsequence("reservation.hold_created", "payment.succeeded",
                "reservation.confirmed", "guest.portal_login");
    }

    @Test
    void failedPaymentKeepsHoldAndShowsReason() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String token = read(started, "$.checkoutToken");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();

        webhook(StripeWebhookSigner.paymentIntentEvent("payment_intent.payment_failed", intentId, 10800,
                "requires_payment_method", "Your card has insufficient funds.")).andExpect(status().isOk());

        checkoutStatus(code, token).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.paymentFailureMessage").value("Your card has insufficient funds."));
        assertThat(jdbc.queryForObject("select failure_code from payment where stripe_payment_intent_id = ?",
                String.class, intentId)).isEqualTo("insufficient_funds");

        // Reintento con otra tarjeta: el mismo PaymentIntent acaba cobrado.
        webhook(StripeWebhookSigner.paymentIntentEvent("payment_intent.succeeded", intentId, 10800, "succeeded", null))
                .andExpect(status().isOk());
        checkoutStatus(code, token).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void invalidWebhookSignatureIsRejected() throws Exception {
        String payload = StripeWebhookSigner.paymentIntentEvent("payment_intent.succeeded", "pi_x", 100, "succeeded", null);
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", StripeWebhookSigner.signature(payload,
                                java.time.Instant.now().getEpochSecond(), "whsec_otro_secreto")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("webhook.invalid_signature"));
        // Evento firmado hace una hora: fuera de la tolerancia anti-replay.
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", StripeWebhookSigner.signature(payload,
                                java.time.Instant.now().minusSeconds(3600).getEpochSecond(), StripeWebhookSigner.SECRET)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyKeyReturnsTheSameReservation() throws Exception {
        String key = "key-" + UUID.randomUUID();
        ResultActions first = startCheckout(flexPlanId, checkIn, checkOut, email, key).andExpect(status().isCreated());
        ResultActions second = startCheckout(flexPlanId, checkIn, checkOut, email, key).andExpect(status().isCreated());

        String code = read(first, "$.reservationCode");
        assertThat((String) read(second, "$.reservationCode")).isEqualTo(code);
        assertThat((String) read(second, "$.payment.clientSecret")).isEqualTo(read(first, "$.payment.clientSecret"));
        assertThat(jdbc.queryForObject("select count(*) from reservation where idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
        // El secreto del checkout se rota: el anterior deja de servir.
        checkoutStatus(code, read(first, "$.checkoutToken")).andExpect(status().isNotFound());
        checkoutStatus(code, read(second, "$.checkoutToken")).andExpect(status().isOk());

        // Misma clave con otra estancia: conflicto.
        startCheckout(flexPlanId, checkIn, checkOut.plusDays(1), email, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("checkout.idempotency_key_reused"));
    }

    @Test
    void lastUnitCannotBeHeldTwice() throws Exception {
        startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());

        startCheckout(flexPlanId, checkIn.plusDays(1), checkOut.plusDays(1), "otro-" + email, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("checkout.sold_out"));
        // Estancia encadenada: entra el día que sale la anterior.
        startCheckout(flexPlanId, checkOut, checkOut.plusDays(2), "otro-" + email, null)
                .andExpect(status().isCreated());
    }

    @Test
    void paidIntentIsConfirmedWhenStatusIsPolledEvenWithoutWebhook() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String token = read(started, "$.checkoutToken");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();

        // Aún sin cobrar: sigue pendiente.
        checkoutStatus(code, token).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        // Stripe cobra pero el webhook nunca llega: la página de estado lo descubre al consultar.
        gateway.setStatus(intentId, IntentStatus.SUCCEEDED);
        checkoutStatus(code, token).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where destination = 'EMAIL' and aggregate_id = "
                + "(select id::text from reservation where code = ?)", Integer.class, code)).isEqualTo(1);

        // Si el webhook llega después, no se duplica nada.
        webhook(StripeWebhookSigner.paymentIntentEvent("payment_intent.succeeded", intentId, 10800, "succeeded", null))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select r.paid_amount = p.amount from reservation r "
                + "join payment p on p.reservation_id = r.id where r.code = ?", Boolean.class, code)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where destination = 'EMAIL' and aggregate_id = "
                + "(select id::text from reservation where code = ?)", Integer.class, code)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment where stripe_payment_intent_id = ? "
                + "and status = 'SUCCEEDED'", Integer.class, intentId)).isEqualTo(1);
    }

    @Test
    void expiredHoldWithPaidIntentIsConfirmedInsteadOfExtended() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();
        expireHold(code);

        gateway.setStatus(intentId, IntentStatus.SUCCEEDED);
        holdExpiry.expireDueHolds(100);
        assertThat(reservationStatus(code)).isEqualTo("CONFIRMED");
        assertThat(units()).isZero();
    }

    @Test
    void expiredHoldIsReleasedUnlessPaymentIsInFlight() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();
        expireHold(code);

        // El banco está procesando el pago: no se libera, se amplía la retención.
        gateway.setStatus(intentId, IntentStatus.PROCESSING);
        holdExpiry.expireDueHolds(100);
        assertThat(reservationStatus(code)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select hold_expires_at > now() from reservation where code = ?",
                Boolean.class, code)).isTrue();
        assertThat(units()).isZero();

        // El pago no prospera y la retención vuelve a vencer: se libera la unidad.
        gateway.setStatus(intentId, IntentStatus.REQUIRES_PAYMENT_METHOD);
        expireHold(code);
        holdExpiry.expireDueHolds(100);
        assertThat(reservationStatus(code)).isEqualTo("CANCELLED");
        assertThat(gateway.intent(intentId).status()).isEqualTo(IntentStatus.CANCELED);
        assertThat(jdbc.queryForObject("select status from payment where stripe_payment_intent_id = ?",
                String.class, intentId)).isEqualTo("CANCELLED");
        assertThat(units()).isEqualTo(1);
    }

    @Test
    void customerCanAbandonCheckout() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String token = read(started, "$.checkoutToken");

        cancelCheckout(code, "token-falso").andExpect(status().isNotFound());
        cancelCheckout(code, token).andExpect(status().isNoContent());
        cancelCheckout(code, token).andExpect(status().isNoContent());
        assertThat(reservationStatus(code)).isEqualTo("CANCELLED");
        assertThat(units()).isEqualTo(1);
    }

    @Test
    void paidCheckoutCannotBeAbandoned() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();
        gateway.setStatus(intentId, IntentStatus.SUCCEEDED);

        // Ya cobrado (aunque el webhook no haya llegado): no se libera, se confirma.
        cancelCheckout(code, read(started, "$.checkoutToken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("checkout.not_cancellable"));
        assertThat(reservationStatus(code)).isEqualTo("CONFIRMED");
    }

    @Test
    void checkoutInFlightCannotBeAbandoned() throws Exception {
        ResultActions started = startCheckout(flexPlanId, checkIn, checkOut, email, null).andExpect(status().isCreated());
        String code = read(started, "$.reservationCode");
        String intentId = gateway.intentBySecret(read(started, "$.payment.clientSecret")).id();
        gateway.setStatus(intentId, IntentStatus.PROCESSING);

        cancelCheckout(code, read(started, "$.checkoutToken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("checkout.payment_in_progress"));
        assertThat(reservationStatus(code)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void planWithoutPrepaymentConfirmsImmediately() throws Exception {
        ResultActions started = startCheckout(payAtPropertyPlanId, checkIn, checkOut, email, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment").value(nullValue()))
                .andExpect(jsonPath("$.prepaymentAmount").value(0.0))
                .andExpect(jsonPath("$.amountDueAtProperty").value(390.0));
        String code = read(started, "$.reservationCode");
        assertThat(jdbc.queryForObject("select payment_mode from reservation where code = ?", String.class, code))
                .isEqualTo("AT_PROPERTY");

        // Si el SMTP falla, el outbox reintenta sin perder el email.
        mail.failNextFor(email, 1);
        outbox.processDue(100);
        assertThat(mail.sentTo(email)).isEmpty();
        // Un segundo atrás: el reloj de la JVM (resolución gruesa en Windows) no debe quedar por detrás de now().
        jdbc.update("update outbox_event set next_attempt_at = now() - interval '1 second' where status = 'FAILED'");
        outbox.processDue(100);
        assertThat(mail.sentTo(email)).hasSize(1);
        // El intento fallido no dejó enlaces huérfanos: solo existe el del envío que funcionó.
        assertThat(jdbc.queryForObject("select count(*) from guest_access_token where reservation_id = "
                + "(select id from reservation where code = ?)", Integer.class, code)).isEqualTo(1);
    }

    @Test
    void paymentProviderOutageReleasesTheHold() throws Exception {
        gateway.failNextCreate();
        startCheckout(flexPlanId, checkIn, checkOut, email, null)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment.provider_error"));
        assertThat(units()).isEqualTo(1);
    }

    @Test
    void checkoutRevalidatesPriceRestrictionsAndOccupancy() throws Exception {
        jdbc.update("update rate_plan set min_stay = 5 where id = ?", flexPlanId);
        startCheckout(flexPlanId, checkIn, checkOut, email, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("checkout.rate_not_bookable"))
                .andExpect(jsonPath("$.restrictions[0].code").value("MIN_STAY"));

        mvc.perform(post("/api/v1/public/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(payAtPropertyPlanId, checkIn, checkOut, email, 5, 0, true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("checkout.occupancy_exceeded"));

        mvc.perform(post("/api/v1/public/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(payAtPropertyPlanId, checkIn, checkOut, email, 2, 0, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("acceptTerms")));
    }

    @Test
    void invalidGuestAccessTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/guest/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"inventado-1234567890\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("guest.invalid_access"));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions startCheckout(UUID planId, LocalDate in, LocalDate out, String guestEmail, String key)
            throws Exception {
        var request = post("/api/v1/public/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkoutJson(planId, in, out, guestEmail, 2, 1, true));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String checkoutJson(UUID planId, LocalDate in, LocalDate out, String guestEmail, int adults,
                                       int children, boolean acceptTerms) {
        return """
                {"ratePlanId":"%s","checkIn":"%s","checkOut":"%s","adults":%d,"children":%d,
                 "guest":{"firstName":"Lucía","lastName1":"García","lastName2":"López","email":"%s",
                          "phone":"+34 600 123 456","countryCode":"ESP"},
                 "specialRequests":"Llegaremos tarde <b>sin</b> problema","expectedArrivalTime":"22:30",
                 "locale":"es","acceptTerms":%s,"acceptPrivacy":true,"marketingConsent":false}
                """.formatted(planId, in, out, adults, children, guestEmail, acceptTerms);
    }

    private ResultActions checkoutStatus(String code, String token) throws Exception {
        return mvc.perform(get("/api/v1/public/checkout/" + code).header(CheckoutController.CHECKOUT_TOKEN_HEADER, token));
    }

    private ResultActions cancelCheckout(String code, String token) throws Exception {
        return mvc.perform(post("/api/v1/public/checkout/" + code + "/cancel")
                .header(CheckoutController.CHECKOUT_TOKEN_HEADER, token));
    }

    private ResultActions webhook(String payload) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Stripe-Signature", StripeWebhookSigner.signature(payload)));
    }

    private void expireHold(String code) {
        jdbc.update("update reservation set hold_expires_at = now() - interval '1 minute' where code = ?", code);
    }

    private String reservationStatus(String code) {
        return jdbc.queryForObject("select status from reservation where code = ?", String.class, code);
    }

    private int units() {
        return inventory.availableUnitsForStay(List.of(roomTypeId), checkIn, checkOut).get(roomTypeId);
    }
}
