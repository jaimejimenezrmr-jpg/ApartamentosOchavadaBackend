-- =====================================================================================
-- V4 · Comunicaciones SES.HOSPEDAJES (Servicio de Comunicación v3.1.2)
--   * cancels_report_id: una anulación (operación B) referencia la comunicación que anula.
--   * En una reserva de hospedaje (RH) del titular solo se exigen nombre, primer apellido y un
--     contacto; documento, nacimiento, nacionalidad, sexo y dirección pasan a ser opcionales en la
--     instantánea. Las reglas del parte de viajeros (PV) las aplica la aplicación antes de enviarlo.
--   * Límites de la especificación: número de documento 15 caracteres, número de soporte 9.
-- =====================================================================================
ALTER TABLE traveler_report
    ADD COLUMN cancels_report_id UUID,
    ADD CONSTRAINT fk_traveler_report_cancels FOREIGN KEY (cancels_report_id) REFERENCES traveler_report (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_traveler_report_cancellation CHECK (
        (operation = 'B' AND cancels_report_id IS NOT NULL) OR (operation <> 'B' AND cancels_report_id IS NULL));

ALTER TABLE traveler_report_person
    ALTER COLUMN sex DROP NOT NULL,
    ALTER COLUMN birth_date DROP NOT NULL,
    ALTER COLUMN nationality DROP NOT NULL,
    ALTER COLUMN address_line DROP NOT NULL,
    ALTER COLUMN postal_code DROP NOT NULL,
    ALTER COLUMN country_code DROP NOT NULL,
    DROP CONSTRAINT ck_trp_sex,
    DROP CONSTRAINT ck_trp_nationality,
    DROP CONSTRAINT ck_trp_country,
    DROP CONSTRAINT ck_trp_municipality,
    ADD CONSTRAINT ck_trp_sex CHECK (sex IS NULL OR sex IN ('H', 'M', 'O')),
    ADD CONSTRAINT ck_trp_nationality CHECK (nationality IS NULL OR nationality ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_trp_country CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{3}$'),
    -- Residentes en España: código de municipio INE; resto: nombre del municipio.
    ADD CONSTRAINT ck_trp_municipality CHECK (country_code IS DISTINCT FROM 'ESP' OR municipality_code ~ '^[0-9]{5}$'),
    ADD CONSTRAINT ck_trp_city CHECK (country_code IS NULL OR country_code = 'ESP' OR city IS NOT NULL),
    ADD CONSTRAINT ck_trp_document_length CHECK (document_number IS NULL OR length(document_number) <= 15),
    ADD CONSTRAINT ck_trp_support_length CHECK (document_support_number IS NULL OR length(document_support_number) <= 9);

CREATE INDEX ix_traveler_report_submitted ON traveler_report (next_attempt_at) WHERE status = 'SUBMITTED';
