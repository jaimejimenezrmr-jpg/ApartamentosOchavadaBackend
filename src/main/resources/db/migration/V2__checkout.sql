-- =====================================================================================
-- V2 · Checkout online
--   * checkout_token_hash: secreto de un solo uso que la web recibe en el paso 1 para consultar
--     el estado del pago y cancelar la retención sin estar autenticada (solo se guarda el hash).
--   * idempotency_key: evita reservas duplicadas por doble clic o reintentos de red
--     (cabecera Idempotency-Key del paso 1).
--   * terms_accepted_at: aceptación de las condiciones de reserva (prueba de consentimiento).
-- =====================================================================================
ALTER TABLE reservation
    ADD COLUMN checkout_token_hash VARCHAR(64),
    ADD COLUMN idempotency_key     VARCHAR(100),
    ADD COLUMN terms_accepted_at   TIMESTAMPTZ;

CREATE UNIQUE INDEX ux_reservation_idempotency_key ON reservation (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Búsqueda de pagos pendientes de una reserva al expirar retenciones.
CREATE INDEX ix_payment_reservation_status ON payment (reservation_id, status);
