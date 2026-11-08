-- =====================================================================================
-- V3 · PMS y facturación
--   * invoice.verifactu_record_timestamp: fecha-hora-huso exacta con la que se calculó la huella
--     VERI*FACTU (FechaHoraHusoGenRegistro). Es necesaria para reproducir y verificar la cadena,
--     por lo que pasa a formar parte de los datos inmutables de la factura.
--   * payment.reference: referencia del cobro en el alojamiento (nº de operación del datáfono,
--     de la transferencia...).
-- =====================================================================================
ALTER TABLE invoice
    ADD COLUMN verifactu_record_timestamp VARCHAR(30);

ALTER TABLE payment
    ADD COLUMN reference VARCHAR(100);

CREATE OR REPLACE FUNCTION trg_invoice_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Las facturas emitidas no pueden eliminarse (factura %)', OLD.full_number
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (NEW.series_id, NEW.number, NEW.full_number, NEW.invoice_type, NEW.reservation_id,
        NEW.rectified_invoice_id, NEW.rectification_reason, NEW.issue_date, NEW.operation_date,
        NEW.issuer_name, NEW.issuer_tax_id, NEW.issuer_address, NEW.customer_name, NEW.customer_tax_id,
        NEW.customer_address, NEW.customer_country_code, NEW.taxable_base, NEW.vat_amount,
        NEW.total_amount, NEW.currency, NEW.issued_by_user_id, NEW.created_at, NEW.verifactu_hash,
        NEW.verifactu_previous_hash, NEW.verifactu_record_timestamp)
        IS DISTINCT FROM
       (OLD.series_id, OLD.number, OLD.full_number, OLD.invoice_type, OLD.reservation_id,
        OLD.rectified_invoice_id, OLD.rectification_reason, OLD.issue_date, OLD.operation_date,
        OLD.issuer_name, OLD.issuer_tax_id, OLD.issuer_address, OLD.customer_name, OLD.customer_tax_id,
        OLD.customer_address, OLD.customer_country_code, OLD.taxable_base, OLD.vat_amount,
        OLD.total_amount, OLD.currency, OLD.issued_by_user_id, OLD.created_at, OLD.verifactu_hash,
        OLD.verifactu_previous_hash, OLD.verifactu_record_timestamp) THEN
        RAISE EXCEPTION 'Los datos fiscales de la factura % son inmutables; emita una factura rectificativa', OLD.full_number
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

-- Búsqueda de huéspedes por nombre, email, teléfono o documento desde recepción.
CREATE INDEX ix_guest_phone ON guest (phone);
CREATE INDEX ix_reservation_created ON reservation (created_at DESC);
CREATE INDEX ix_invoice_created ON invoice (created_at DESC);
