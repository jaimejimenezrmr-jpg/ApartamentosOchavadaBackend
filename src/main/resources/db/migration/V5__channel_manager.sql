-- =====================================================================================
-- V5 · Channel manager (Beds24)
--   * integration_sync_state: marcas de sincronización (p. ej. última consulta de reservas
--     modificadas en Beds24) para que el sondeo periódico sea incremental y sobreviva a reinicios.
--   * reservation.channel_sync_note: aviso de la última importación desde el canal (p. ej. unidad
--     ocupada al importar una reserva de OTA), visible en recepción.
-- =====================================================================================
CREATE TABLE integration_sync_state
(
    name           VARCHAR(50) NOT NULL,
    last_synced_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_integration_sync_state PRIMARY KEY (name)
);

ALTER TABLE reservation
    ADD COLUMN channel_sync_note VARCHAR(500);

CREATE INDEX ix_reservation_channel_reference ON reservation (channel_booking_reference)
    WHERE channel_booking_reference IS NOT NULL;
