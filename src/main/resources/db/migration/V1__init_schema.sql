-- =====================================================================================
-- Apartamentos Ochavada · Esquema inicial (PostgreSQL 16)
-- -------------------------------------------------------------------------------------
-- Convenciones:
--   * Claves primarias UUID (gen_random_uuid, nativo desde PG 13) salvo tablas de log
--     de alto volumen (BIGINT IDENTITY).
--   * Todas las marcas temporales en TIMESTAMPTZ (UTC). Las fechas de estancia en DATE
--     con semántica de intervalo semiabierto [check_in, check_out).
--   * Enumerados como VARCHAR + CHECK (mapeo directo con @Enumerated(STRING) en JPA).
--   * Importes en NUMERIC(12,2), moneda ISO 4217.
--   * Columna "version" para bloqueo optimista en las entidades editables.
-- =====================================================================================

-- Necesaria para restricciones EXCLUDE que mezclan igualdad (=) y solapamiento (&&).
-- Es una extensión "trusted": la puede crear el propietario de la base de datos.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- -------------------------------------------------------------------------------------
-- Funciones auxiliares
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION trg_set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- Tablas de auditoría de solo inserción: impide UPDATE y DELETE.
CREATE OR REPLACE FUNCTION trg_forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'La tabla % es de solo inserción: operación % no permitida', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- =====================================================================================
-- 1. USUARIOS DEL BACKOFFICE Y SESIONES
-- =====================================================================================
CREATE TABLE app_user
(
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    email                 VARCHAR(254) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    full_name             VARCHAR(150) NOT NULL,
    phone                 VARCHAR(30),
    role                  VARCHAR(20)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'RECEPTIONIST', 'HOUSEKEEPING')),
    CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_failed_attempts CHECK (failed_login_attempts >= 0)
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (email);
CREATE TRIGGER app_user_updated_at
    BEFORE UPDATE
    ON app_user
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Refresh tokens opacos (solo se almacena su hash SHA-256) con rotación y detección de reutilización
-- por familia: si se presenta un token ya rotado se revoca toda la familia.
CREATE TABLE refresh_token
(
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL,
    family_id         UUID        NOT NULL,
    token_hash        VARCHAR(64) NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ,
    revocation_reason VARCHAR(30),
    replaced_by_id    UUID,
    created_ip        VARCHAR(45),
    user_agent        VARCHAR(512),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_token_replaced_by FOREIGN KEY (replaced_by_id) REFERENCES refresh_token (id) ON DELETE SET NULL,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_token_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL) OR
        (revoked_at IS NOT NULL AND revocation_reason IN
                                    ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'USER_DISABLED', 'ADMIN_REVOKED', 'PASSWORD_CHANGED')))
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);
CREATE INDEX ix_refresh_token_expires ON refresh_token (expires_at);

-- =====================================================================================
-- 2. CATÁLOGO: TIPOLOGÍAS, UNIDADES (HABITACIONES/APARTAMENTOS) Y BLOQUEOS
-- =====================================================================================
-- Tipología comercial (lo que se vende en la web y en las OTAs; equivale a "room" en Beds24).
CREATE TABLE room_type
(
    id                   UUID          NOT NULL DEFAULT gen_random_uuid(),
    code                 VARCHAR(30)   NOT NULL,
    slug                 VARCHAR(120)  NOT NULL,
    name                 VARCHAR(120)  NOT NULL,
    short_description    VARCHAR(500),
    description          TEXT,
    max_occupancy        SMALLINT      NOT NULL,
    max_adults           SMALLINT      NOT NULL,
    max_children         SMALLINT      NOT NULL DEFAULT 0,
    bedrooms             SMALLINT      NOT NULL DEFAULT 1,
    bathrooms            SMALLINT      NOT NULL DEFAULT 1,
    beds_description     VARCHAR(200),
    size_m2              NUMERIC(6, 2),
    base_price           NUMERIC(12, 2) NOT NULL,
    currency             CHAR(3)       NOT NULL DEFAULT 'EUR',
    display_order        INTEGER       NOT NULL DEFAULT 0,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    beds24_room_id       BIGINT,
    google_room_id       VARCHAR(100),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_room_type PRIMARY KEY (id),
    CONSTRAINT uq_room_type_code UNIQUE (code),
    CONSTRAINT uq_room_type_slug UNIQUE (slug),
    CONSTRAINT uq_room_type_beds24 UNIQUE (beds24_room_id),
    CONSTRAINT ck_room_type_occupancy CHECK (max_occupancy >= 1 AND max_adults >= 1 AND max_children >= 0
        AND max_adults <= max_occupancy AND max_children <= max_occupancy),
    CONSTRAINT ck_room_type_rooms CHECK (bedrooms >= 0 AND bathrooms >= 0),
    CONSTRAINT ck_room_type_size CHECK (size_m2 IS NULL OR size_m2 > 0),
    CONSTRAINT ck_room_type_price CHECK (base_price >= 0),
    CONSTRAINT ck_room_type_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE TRIGGER room_type_updated_at
    BEFORE UPDATE
    ON room_type
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

CREATE TABLE room_type_photo
(
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    room_type_id  UUID          NOT NULL,
    url           VARCHAR(1024) NOT NULL,
    alt_text      VARCHAR(250)  NOT NULL,
    display_order INTEGER       NOT NULL DEFAULT 0,
    is_cover      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_room_type_photo PRIMARY KEY (id),
    CONSTRAINT fk_room_type_photo_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE CASCADE
);
CREATE INDEX ix_room_type_photo_type ON room_type_photo (room_type_id, display_order);
CREATE UNIQUE INDEX ux_room_type_photo_cover ON room_type_photo (room_type_id) WHERE is_cover;

CREATE TABLE amenity
(
    id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    code     VARCHAR(50)  NOT NULL,
    name     VARCHAR(100) NOT NULL,
    icon     VARCHAR(50),
    category VARCHAR(30)  NOT NULL DEFAULT 'GENERAL',
    CONSTRAINT pk_amenity PRIMARY KEY (id),
    CONSTRAINT uq_amenity_code UNIQUE (code),
    CONSTRAINT ck_amenity_category CHECK (category IN
                                          ('GENERAL', 'KITCHEN', 'BATHROOM', 'BEDROOM', 'ENTERTAINMENT', 'ACCESSIBILITY', 'OUTDOOR', 'SERVICES'))
);

CREATE TABLE room_type_amenity
(
    room_type_id UUID NOT NULL,
    amenity_id   UUID NOT NULL,
    CONSTRAINT pk_room_type_amenity PRIMARY KEY (room_type_id, amenity_id),
    CONSTRAINT fk_rta_room_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE CASCADE,
    CONSTRAINT fk_rta_amenity FOREIGN KEY (amenity_id) REFERENCES amenity (id) ON DELETE CASCADE
);
CREATE INDEX ix_room_type_amenity_amenity ON room_type_amenity (amenity_id);

-- Unidad física (apartamento/habitación concreta). Es la fila del Rack/Gantt.
CREATE TABLE room
(
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    room_type_id            UUID        NOT NULL,
    number                  VARCHAR(10) NOT NULL,
    name                    VARCHAR(100),
    floor                   SMALLINT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    housekeeping_status     VARCHAR(20) NOT NULL DEFAULT 'CLEAN',
    housekeeping_updated_at TIMESTAMPTZ,
    beds24_unit_id          INTEGER,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_room PRIMARY KEY (id),
    CONSTRAINT fk_room_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE RESTRICT,
    CONSTRAINT uq_room_number UNIQUE (number),
    CONSTRAINT uq_room_beds24_unit UNIQUE (room_type_id, beds24_unit_id),
    CONSTRAINT ck_room_status CHECK (status IN ('ACTIVE', 'OUT_OF_ORDER', 'INACTIVE')),
    CONSTRAINT ck_room_housekeeping CHECK (housekeeping_status IN
                                           ('CLEAN', 'DIRTY', 'IN_PROGRESS', 'INSPECTED', 'OUT_OF_SERVICE'))
);
CREATE INDEX ix_room_type ON room (room_type_id);
CREATE TRIGGER room_updated_at
    BEFORE UPDATE
    ON room
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Días bloqueados por unidad (mantenimiento, uso propio...). Sin solapamientos por unidad.
CREATE TABLE room_block
(
    id                 UUID        NOT NULL DEFAULT gen_random_uuid(),
    room_id            UUID        NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    block_type         VARCHAR(20) NOT NULL,
    reason             VARCHAR(500),
    created_by_user_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_room_block PRIMARY KEY (id),
    CONSTRAINT fk_room_block_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE CASCADE,
    CONSTRAINT fk_room_block_user FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_room_block_dates CHECK (end_date > start_date),
    CONSTRAINT ck_room_block_type CHECK (block_type IN ('MAINTENANCE', 'OWNER_USE', 'OUT_OF_ORDER', 'OTHER')),
    CONSTRAINT ex_room_block_overlap EXCLUDE USING gist (
        room_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
        )
);

-- =====================================================================================
-- 3. MOTOR DE TARIFAS Y RESTRICCIONES
-- =====================================================================================
CREATE TABLE rate_plan
(
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    room_type_id           UUID           NOT NULL,
    code                   VARCHAR(30)    NOT NULL,
    name                   VARCHAR(120)   NOT NULL,
    description            VARCHAR(1000),
    meal_plan              VARCHAR(20)    NOT NULL DEFAULT 'ROOM_ONLY',
    refundable             BOOLEAN        NOT NULL DEFAULT TRUE,
    free_cancellation_days SMALLINT,
    prepayment_percent     NUMERIC(5, 2)  NOT NULL DEFAULT 100,
    base_price             NUMERIC(12, 2) NOT NULL,
    min_stay               SMALLINT       NOT NULL DEFAULT 1,
    max_stay               SMALLINT,
    is_default             BOOLEAN        NOT NULL DEFAULT FALSE,
    active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    channel_rate_code      VARCHAR(50),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_rate_plan PRIMARY KEY (id),
    CONSTRAINT fk_rate_plan_room_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE CASCADE,
    CONSTRAINT uq_rate_plan_code UNIQUE (room_type_id, code),
    CONSTRAINT ck_rate_plan_meal CHECK (meal_plan IN ('ROOM_ONLY', 'BREAKFAST', 'HALF_BOARD', 'FULL_BOARD')),
    CONSTRAINT ck_rate_plan_cancellation CHECK (
        (refundable AND free_cancellation_days IS NOT NULL AND free_cancellation_days >= 0) OR
        (NOT refundable AND free_cancellation_days IS NULL)),
    CONSTRAINT ck_rate_plan_prepayment CHECK (prepayment_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_rate_plan_price CHECK (base_price >= 0),
    CONSTRAINT ck_rate_plan_stay CHECK (min_stay >= 1 AND (max_stay IS NULL OR max_stay >= min_stay))
);
CREATE UNIQUE INDEX ux_rate_plan_default ON rate_plan (room_type_id) WHERE is_default;
CREATE TRIGGER rate_plan_updated_at
    BEFORE UPDATE
    ON rate_plan
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Temporadas: precio por noche en un rango de fechas [start_date, end_date). Sin solapamientos por plan.
CREATE TABLE rate_season
(
    id            UUID           NOT NULL DEFAULT gen_random_uuid(),
    rate_plan_id  UUID           NOT NULL,
    name          VARCHAR(100)   NOT NULL,
    start_date    DATE           NOT NULL,
    end_date      DATE           NOT NULL,
    nightly_price NUMERIC(12, 2) NOT NULL,
    weekend_price NUMERIC(12, 2),
    min_stay      SMALLINT,
    max_stay      SMALLINT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_rate_season PRIMARY KEY (id),
    CONSTRAINT fk_rate_season_plan FOREIGN KEY (rate_plan_id) REFERENCES rate_plan (id) ON DELETE CASCADE,
    CONSTRAINT ck_rate_season_dates CHECK (end_date > start_date),
    CONSTRAINT ck_rate_season_price CHECK (nightly_price >= 0 AND (weekend_price IS NULL OR weekend_price >= 0)),
    CONSTRAINT ck_rate_season_stay CHECK ((min_stay IS NULL OR min_stay >= 1) AND
                                          (max_stay IS NULL OR max_stay >= COALESCE(min_stay, 1))),
    CONSTRAINT ex_rate_season_overlap EXCLUDE USING gist (
        rate_plan_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
        )
);
CREATE TRIGGER rate_season_updated_at
    BEFORE UPDATE
    ON rate_season
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Ajustes diarios: precio puntual y restricciones (estancia mínima/máxima, CTA, CTD, stop-sell).
-- Prioridad de resolución de precio: daily_rate.price > rate_season > rate_plan.base_price.
CREATE TABLE daily_rate
(
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    rate_plan_id        UUID        NOT NULL,
    stay_date           DATE        NOT NULL,
    price               NUMERIC(12, 2),
    min_stay            SMALLINT,
    max_stay            SMALLINT,
    closed_to_arrival   BOOLEAN     NOT NULL DEFAULT FALSE,
    closed_to_departure BOOLEAN     NOT NULL DEFAULT FALSE,
    stop_sell           BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by_user_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_daily_rate PRIMARY KEY (id),
    CONSTRAINT fk_daily_rate_plan FOREIGN KEY (rate_plan_id) REFERENCES rate_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_daily_rate_user FOREIGN KEY (updated_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_daily_rate UNIQUE (rate_plan_id, stay_date),
    CONSTRAINT ck_daily_rate_price CHECK (price IS NULL OR price >= 0),
    CONSTRAINT ck_daily_rate_stay CHECK ((min_stay IS NULL OR min_stay >= 1) AND
                                         (max_stay IS NULL OR max_stay >= COALESCE(min_stay, 1)))
);
CREATE INDEX ix_daily_rate_date ON daily_rate (stay_date);
CREATE TRIGGER daily_rate_updated_at
    BEFORE UPDATE
    ON daily_rate
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- =====================================================================================
-- 4. HUÉSPEDES (datos exigidos por RD 933/2021, anexo I)
-- =====================================================================================
CREATE TABLE guest
(
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    first_name              VARCHAR(100) NOT NULL,
    last_name1              VARCHAR(100) NOT NULL,
    last_name2              VARCHAR(100),
    sex                     CHAR(1),
    birth_date              DATE,
    nationality             CHAR(3),
    document_type           VARCHAR(5),
    document_number         VARCHAR(30),
    document_support_number VARCHAR(30),
    document_issue_date     DATE,
    email                   VARCHAR(254),
    phone                   VARCHAR(30),
    phone_landline          VARCHAR(30),
    address_line            VARCHAR(250),
    address_complement      VARCHAR(250),
    postal_code             VARCHAR(20),
    city                    VARCHAR(100),
    municipality_code       CHAR(5),
    province                VARCHAR(100),
    country_code            CHAR(3),
    preferred_locale        VARCHAR(10)  NOT NULL DEFAULT 'es',
    marketing_consent       BOOLEAN      NOT NULL DEFAULT FALSE,
    privacy_accepted_at     TIMESTAMPTZ,
    notes                   TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_guest PRIMARY KEY (id),
    -- Códigos SES.HOSPEDAJES: H (hombre), M (mujer), O (otro)
    CONSTRAINT ck_guest_sex CHECK (sex IS NULL OR sex IN ('H', 'M', 'O')),
    -- Tipos de documento SES.HOSPEDAJES
    CONSTRAINT ck_guest_document_type CHECK (document_type IS NULL OR document_type IN ('NIF', 'NIE', 'PAS', 'OTRO')),
    CONSTRAINT ck_guest_document_pair CHECK ((document_type IS NULL) = (document_number IS NULL)),
    -- ISO 3166-1 alfa-3
    CONSTRAINT ck_guest_nationality CHECK (nationality IS NULL OR nationality ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_guest_country CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{3}$'),
    -- Código de municipio INE (obligatorio para residentes en España en la comunicación)
    CONSTRAINT ck_guest_municipality CHECK (municipality_code IS NULL OR municipality_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_guest_email_lower CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_guest_birth_date CHECK (birth_date IS NULL OR birth_date > DATE '1900-01-01')
);
CREATE INDEX ix_guest_email ON guest (email);
CREATE INDEX ix_guest_document ON guest (document_number);
CREATE INDEX ix_guest_name ON guest (lower(last_name1), lower(first_name));
CREATE TRIGGER guest_updated_at
    BEFORE UPDATE
    ON guest
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- =====================================================================================
-- 5. RESERVAS
-- =====================================================================================
CREATE TABLE reservation
(
    id                           UUID           NOT NULL DEFAULT gen_random_uuid(),
    code                         VARCHAR(20)    NOT NULL,
    status                       VARCHAR(20)    NOT NULL,
    channel                      VARCHAR(20)    NOT NULL,
    beds24_booking_id            BIGINT,
    channel_booking_reference    VARCHAR(100),
    room_type_id                 UUID           NOT NULL,
    room_id                      UUID,
    rate_plan_id                 UUID,
    holder_guest_id              UUID           NOT NULL,
    check_in_date                DATE           NOT NULL,
    check_out_date               DATE           NOT NULL,
    nights                       INTEGER GENERATED ALWAYS AS (check_out_date - check_in_date) STORED,
    expected_arrival_time        TIME,
    adults                       SMALLINT       NOT NULL,
    children                     SMALLINT       NOT NULL DEFAULT 0,
    total_amount                 NUMERIC(12, 2) NOT NULL,
    paid_amount                  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    commission_amount            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    currency                     CHAR(3)        NOT NULL DEFAULT 'EUR',
    payment_mode                 VARCHAR(20)    NOT NULL,
    special_requests             TEXT,
    internal_notes               TEXT,
    locale                       VARCHAR(10)    NOT NULL DEFAULT 'es',
    hold_expires_at              TIMESTAMPTZ,
    online_checkin_completed_at  TIMESTAMPTZ,
    checked_in_at                TIMESTAMPTZ,
    checked_out_at               TIMESTAMPTZ,
    cancelled_at                 TIMESTAMPTZ,
    cancellation_reason          VARCHAR(500),
    created_by_user_id           UUID,
    created_at                   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                      BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_reservation PRIMARY KEY (id),
    CONSTRAINT fk_reservation_room_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_rate_plan FOREIGN KEY (rate_plan_id) REFERENCES rate_plan (id) ON DELETE SET NULL,
    CONSTRAINT fk_reservation_holder FOREIGN KEY (holder_guest_id) REFERENCES guest (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_created_by FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_reservation_code UNIQUE (code),
    CONSTRAINT uq_reservation_beds24 UNIQUE (beds24_booking_id),
    CONSTRAINT ck_reservation_status CHECK (status IN
                                            ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_reservation_channel CHECK (channel IN
                                             ('DIRECT', 'BOOKING_COM', 'AIRBNB', 'EXPEDIA', 'GOOGLE', 'BEDS24_OTHER', 'PHONE', 'WALK_IN')),
    CONSTRAINT ck_reservation_payment_mode CHECK (payment_mode IN ('ONLINE', 'AT_PROPERTY', 'OTA_COLLECT')),
    CONSTRAINT ck_reservation_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT ck_reservation_guests CHECK (adults >= 1 AND children >= 0),
    CONSTRAINT ck_reservation_amounts CHECK (total_amount >= 0 AND paid_amount >= 0 AND commission_amount >= 0),
    CONSTRAINT ck_reservation_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reservation_checked_in CHECK (status NOT IN ('CHECKED_IN', 'CHECKED_OUT') OR
                                                (checked_in_at IS NOT NULL AND room_id IS NOT NULL)),
    CONSTRAINT ck_reservation_checked_out CHECK (status <> 'CHECKED_OUT' OR checked_out_at IS NOT NULL),
    CONSTRAINT ck_reservation_cancelled CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    CONSTRAINT ck_reservation_hold CHECK (status <> 'PENDING_PAYMENT' OR hold_expires_at IS NOT NULL),
    -- Anti-overbooking a nivel de base de datos: una unidad no puede tener dos reservas vivas solapadas.
    CONSTRAINT ex_reservation_room_overlap EXCLUDE USING gist (
        room_id WITH =,
        daterange(check_in_date, check_out_date, '[)') WITH &&
        ) WHERE (room_id IS NOT NULL AND status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN'))
);
CREATE INDEX ix_reservation_check_in ON reservation (check_in_date);
CREATE INDEX ix_reservation_check_out ON reservation (check_out_date);
CREATE INDEX ix_reservation_status ON reservation (status);
CREATE INDEX ix_reservation_holder ON reservation (holder_guest_id);
CREATE INDEX ix_reservation_room_dates ON reservation (room_id, check_in_date, check_out_date);
CREATE INDEX ix_reservation_hold ON reservation (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE TRIGGER reservation_updated_at
    BEFORE UPDATE
    ON reservation
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Viajeros de la reserva (todos deben comunicarse a SES; parentesco obligatorio si hay menores).
CREATE TABLE reservation_guest
(
    reservation_id UUID        NOT NULL,
    guest_id       UUID        NOT NULL,
    is_holder      BOOLEAN     NOT NULL DEFAULT FALSE,
    relationship   VARCHAR(2),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_reservation_guest PRIMARY KEY (reservation_id, guest_id),
    CONSTRAINT fk_reservation_guest_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_guest_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE RESTRICT,
    -- Códigos de parentesco SES.HOSPEDAJES
    CONSTRAINT ck_reservation_guest_relationship CHECK (relationship IS NULL OR relationship IN
                                                        ('AB', 'BA', 'BN', 'CD', 'CY', 'HJ', 'HR', 'NI', 'PM', 'SB', 'SG', 'TI', 'TU', 'YN', 'OT'))
);
CREATE UNIQUE INDEX ux_reservation_guest_holder ON reservation_guest (reservation_id) WHERE is_holder;
CREATE INDEX ix_reservation_guest_guest ON reservation_guest (guest_id);

-- Desglose de precio por noche (base para facturación e informes de ingresos).
CREATE TABLE reservation_night
(
    reservation_id UUID           NOT NULL,
    stay_date      DATE           NOT NULL,
    price          NUMERIC(12, 2) NOT NULL,
    rate_plan_id   UUID,
    CONSTRAINT pk_reservation_night PRIMARY KEY (reservation_id, stay_date),
    CONSTRAINT fk_reservation_night_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_night_rate_plan FOREIGN KEY (rate_plan_id) REFERENCES rate_plan (id) ON DELETE SET NULL,
    CONSTRAINT ck_reservation_night_price CHECK (price >= 0)
);
CREATE INDEX ix_reservation_night_date ON reservation_night (stay_date);

-- Enlaces de autoservicio del huésped (check-in online y llave virtual). Solo se guarda el hash.
CREATE TABLE guest_access_token
(
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID        NOT NULL,
    token_hash     VARCHAR(64) NOT NULL,
    valid_from     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    last_used_at   TIMESTAMPTZ,
    use_count      INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_guest_access_token PRIMARY KEY (id),
    CONSTRAINT fk_guest_access_token_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE,
    CONSTRAINT uq_guest_access_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_guest_access_token_window CHECK (expires_at > valid_from),
    CONSTRAINT ck_guest_access_token_uses CHECK (use_count >= 0)
);
CREATE INDEX ix_guest_access_token_reservation ON guest_access_token (reservation_id);

-- =====================================================================================
-- 6. PAGOS (Stripe y cobros en el alojamiento)
-- =====================================================================================
CREATE TABLE payment
(
    id                       UUID           NOT NULL DEFAULT gen_random_uuid(),
    reservation_id           UUID           NOT NULL,
    provider                 VARCHAR(20)    NOT NULL,
    status                   VARCHAR(20)    NOT NULL,
    amount                   NUMERIC(12, 2) NOT NULL,
    refunded_amount          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    currency                 CHAR(3)        NOT NULL DEFAULT 'EUR',
    stripe_payment_intent_id VARCHAR(255),
    stripe_charge_id         VARCHAR(255),
    stripe_customer_id       VARCHAR(255),
    card_brand               VARCHAR(20),
    card_last4               CHAR(4),
    failure_code             VARCHAR(100),
    failure_message          VARCHAR(500),
    paid_at                  TIMESTAMPTZ,
    registered_by_user_id    UUID,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_user FOREIGN KEY (registered_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_payment_intent UNIQUE (stripe_payment_intent_id),
    CONSTRAINT ck_payment_provider CHECK (provider IN ('STRIPE', 'CASH', 'CARD_TERMINAL', 'BANK_TRANSFER', 'OTA')),
    CONSTRAINT ck_payment_status CHECK (status IN
                                        ('PENDING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    CONSTRAINT ck_payment_amounts CHECK (amount > 0 AND refunded_amount >= 0 AND refunded_amount <= amount),
    CONSTRAINT ck_payment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_stripe CHECK (provider <> 'STRIPE' OR stripe_payment_intent_id IS NOT NULL),
    CONSTRAINT ck_payment_card_last4 CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$')
);
CREATE INDEX ix_payment_reservation ON payment (reservation_id);
CREATE INDEX ix_payment_status ON payment (status);
CREATE TRIGGER payment_updated_at
    BEFORE UPDATE
    ON payment
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- =====================================================================================
-- 7. FACTURACIÓN LEGAL (RD 1619/2012; preparada para VERI*FACTU, RD 1007/2023)
-- =====================================================================================
-- Series con numeración correlativa sin huecos por ejercicio (se asigna con SELECT ... FOR UPDATE).
CREATE TABLE invoice_series
(
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    code         VARCHAR(10) NOT NULL,
    fiscal_year  SMALLINT    NOT NULL,
    invoice_type VARCHAR(2)  NOT NULL,
    description  VARCHAR(200),
    next_number  INTEGER     NOT NULL DEFAULT 1,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_invoice_series PRIMARY KEY (id),
    CONSTRAINT uq_invoice_series UNIQUE (code, fiscal_year),
    CONSTRAINT ck_invoice_series_code CHECK (code ~ '^[A-Z0-9]{1,10}$'),
    CONSTRAINT ck_invoice_series_year CHECK (fiscal_year BETWEEN 2000 AND 2999),
    CONSTRAINT ck_invoice_series_type CHECK (invoice_type IN ('F1', 'F2', 'R1', 'R2', 'R3', 'R4', 'R5')),
    CONSTRAINT ck_invoice_series_next CHECK (next_number >= 1)
);
CREATE TRIGGER invoice_series_updated_at
    BEFORE UPDATE
    ON invoice_series
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

CREATE TABLE invoice
(
    id                      UUID           NOT NULL DEFAULT gen_random_uuid(),
    series_id               UUID           NOT NULL,
    number                  INTEGER        NOT NULL,
    full_number             VARCHAR(30)    NOT NULL,
    invoice_type            VARCHAR(2)     NOT NULL,
    status                  VARCHAR(20)    NOT NULL DEFAULT 'ISSUED',
    reservation_id          UUID,
    rectified_invoice_id    UUID,
    rectification_reason    VARCHAR(500),
    issue_date              DATE           NOT NULL,
    operation_date          DATE,
    issuer_name             VARCHAR(200)   NOT NULL,
    issuer_tax_id           VARCHAR(20)    NOT NULL,
    issuer_address          VARCHAR(500)   NOT NULL,
    customer_name           VARCHAR(200),
    customer_tax_id         VARCHAR(30),
    customer_address        VARCHAR(500),
    customer_country_code   CHAR(3),
    taxable_base            NUMERIC(12, 2) NOT NULL,
    vat_amount              NUMERIC(12, 2) NOT NULL,
    total_amount            NUMERIC(12, 2) NOT NULL,
    currency                CHAR(3)        NOT NULL DEFAULT 'EUR',
    notes                   VARCHAR(1000),
    verifactu_status        VARCHAR(20)    NOT NULL DEFAULT 'NOT_APPLICABLE',
    verifactu_hash          VARCHAR(64),
    verifactu_previous_hash VARCHAR(64),
    verifactu_csv           VARCHAR(100),
    verifactu_sent_at       TIMESTAMPTZ,
    issued_by_user_id       UUID,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_invoice PRIMARY KEY (id),
    CONSTRAINT fk_invoice_series FOREIGN KEY (series_id) REFERENCES invoice_series (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_rectified FOREIGN KEY (rectified_invoice_id) REFERENCES invoice (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_user FOREIGN KEY (issued_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_invoice_number UNIQUE (series_id, number),
    CONSTRAINT uq_invoice_full_number UNIQUE (full_number),
    CONSTRAINT ck_invoice_type CHECK (invoice_type IN ('F1', 'F2', 'R1', 'R2', 'R3', 'R4', 'R5')),
    CONSTRAINT ck_invoice_status CHECK (status IN ('ISSUED', 'RECTIFIED')),
    CONSTRAINT ck_invoice_number CHECK (number >= 1),
    -- Las rectificativas deben referenciar la factura rectificada y su motivo.
    CONSTRAINT ck_invoice_rectification CHECK (
        (invoice_type LIKE 'R%' AND rectified_invoice_id IS NOT NULL AND rectification_reason IS NOT NULL) OR
        (invoice_type LIKE 'F%' AND rectified_invoice_id IS NULL)),
    -- La factura completa (F1) exige identificación fiscal del destinatario.
    CONSTRAINT ck_invoice_customer CHECK (invoice_type <> 'F1' OR (customer_name IS NOT NULL AND customer_tax_id IS NOT NULL)),
    CONSTRAINT ck_invoice_totals CHECK (total_amount = taxable_base + vat_amount),
    CONSTRAINT ck_invoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_invoice_verifactu CHECK (verifactu_status IN
                                           ('NOT_APPLICABLE', 'PENDING', 'SENT', 'ACCEPTED', 'ACCEPTED_WITH_ERRORS', 'REJECTED'))
);
CREATE INDEX ix_invoice_reservation ON invoice (reservation_id);
CREATE INDEX ix_invoice_issue_date ON invoice (issue_date);

CREATE TABLE invoice_line
(
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    invoice_id       UUID           NOT NULL,
    line_number      SMALLINT       NOT NULL,
    description      VARCHAR(500)   NOT NULL,
    quantity         NUMERIC(10, 3) NOT NULL,
    unit_price       NUMERIC(12, 4) NOT NULL,
    discount_percent NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    taxable_base     NUMERIC(12, 2) NOT NULL,
    vat_rate         NUMERIC(5, 2)  NOT NULL,
    vat_amount       NUMERIC(12, 2) NOT NULL,
    line_total       NUMERIC(12, 2) NOT NULL,
    CONSTRAINT pk_invoice_line PRIMARY KEY (id),
    CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id) ON DELETE RESTRICT,
    CONSTRAINT uq_invoice_line UNIQUE (invoice_id, line_number),
    CONSTRAINT ck_invoice_line_quantity CHECK (quantity <> 0),
    CONSTRAINT ck_invoice_line_discount CHECK (discount_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_invoice_line_vat CHECK (vat_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_invoice_line_total CHECK (line_total = taxable_base + vat_amount)
);

-- Inmutabilidad fiscal: una factura emitida no se borra ni se alteran sus datos;
-- solo pueden cambiar el estado (RECTIFIED) y los campos de seguimiento VERI*FACTU.
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
        NEW.verifactu_previous_hash)
        IS DISTINCT FROM
       (OLD.series_id, OLD.number, OLD.full_number, OLD.invoice_type, OLD.reservation_id,
        OLD.rectified_invoice_id, OLD.rectification_reason, OLD.issue_date, OLD.operation_date,
        OLD.issuer_name, OLD.issuer_tax_id, OLD.issuer_address, OLD.customer_name, OLD.customer_tax_id,
        OLD.customer_address, OLD.customer_country_code, OLD.taxable_base, OLD.vat_amount,
        OLD.total_amount, OLD.currency, OLD.issued_by_user_id, OLD.created_at, OLD.verifactu_hash,
        OLD.verifactu_previous_hash) THEN
        RAISE EXCEPTION 'Los datos fiscales de la factura % son inmutables; emita una factura rectificativa', OLD.full_number
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invoice_immutable
    BEFORE UPDATE OR DELETE
    ON invoice
    FOR EACH ROW
EXECUTE FUNCTION trg_invoice_immutable();

CREATE TRIGGER invoice_line_append_only
    BEFORE UPDATE OR DELETE
    ON invoice_line
    FOR EACH ROW
EXECUTE FUNCTION trg_forbid_modification();

-- =====================================================================================
-- 8. CONTROL DE ACCESOS IoT (TTLock)
-- =====================================================================================
CREATE TABLE lock_device
(
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    provider            VARCHAR(20)  NOT NULL DEFAULT 'TTLOCK',
    external_lock_id    BIGINT       NOT NULL,
    name                VARCHAR(100) NOT NULL,
    lock_type           VARCHAR(20)  NOT NULL,
    room_id             UUID,
    lock_mac            VARCHAR(17),
    gateway_id          BIGINT,
    has_gateway         BOOLEAN      NOT NULL DEFAULT TRUE,
    battery_level       SMALLINT,
    battery_updated_at  TIMESTAMPTZ,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_lock_device PRIMARY KEY (id),
    CONSTRAINT fk_lock_device_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE RESTRICT,
    CONSTRAINT uq_lock_device_external UNIQUE (provider, external_lock_id),
    CONSTRAINT ck_lock_device_provider CHECK (provider IN ('TTLOCK')),
    CONSTRAINT ck_lock_device_type CHECK (lock_type IN ('MAIN_ENTRANCE', 'ROOM', 'COMMON_AREA')),
    -- Una cerradura de habitación debe estar vinculada a su unidad; la entrada principal y zonas comunes no.
    CONSTRAINT ck_lock_device_room CHECK ((lock_type = 'ROOM') = (room_id IS NOT NULL)),
    CONSTRAINT ck_lock_device_battery CHECK (battery_level IS NULL OR battery_level BETWEEN 0 AND 100),
    CONSTRAINT ck_lock_device_mac CHECK (lock_mac IS NULL OR lock_mac ~ '^([0-9A-F]{2}:){5}[0-9A-F]{2}$')
);
-- Una sola cerradura activa por unidad.
CREATE UNIQUE INDEX ux_lock_device_room ON lock_device (room_id) WHERE room_id IS NOT NULL AND active;
CREATE TRIGGER lock_device_updated_at
    BEFORE UPDATE
    ON lock_device
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Códigos de teclado temporales generados por reserva (alternativa/respaldo a la apertura remota).
CREATE TABLE lock_passcode
(
    id                   UUID        NOT NULL DEFAULT gen_random_uuid(),
    lock_device_id       UUID        NOT NULL,
    reservation_id       UUID        NOT NULL,
    external_passcode_id BIGINT,
    passcode_hint        VARCHAR(4),
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ NOT NULL,
    status               VARCHAR(20) NOT NULL,
    last_error           VARCHAR(500),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_lock_passcode PRIMARY KEY (id),
    CONSTRAINT fk_lock_passcode_lock FOREIGN KEY (lock_device_id) REFERENCES lock_device (id) ON DELETE CASCADE,
    CONSTRAINT fk_lock_passcode_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE CASCADE,
    CONSTRAINT ck_lock_passcode_window CHECK (valid_to > valid_from),
    CONSTRAINT ck_lock_passcode_status CHECK (status IN ('PENDING', 'ACTIVE', 'DELETED', 'FAILED'))
);
CREATE INDEX ix_lock_passcode_reservation ON lock_passcode (reservation_id);
CREATE UNIQUE INDEX ux_lock_passcode_active ON lock_passcode (lock_device_id, reservation_id)
    WHERE status IN ('PENDING', 'ACTIVE');
CREATE TRIGGER lock_passcode_updated_at
    BEFORE UPDATE
    ON lock_passcode
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Auditoría de aperturas y operaciones sobre cerraduras (append-only, incluye intentos denegados).
CREATE TABLE lock_access_log
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    lock_device_id      UUID,
    reservation_id      UUID,
    guest_access_id     UUID,
    user_id             UUID,
    actor_type          VARCHAR(10)  NOT NULL,
    action              VARCHAR(30)  NOT NULL,
    result              VARCHAR(10)  NOT NULL,
    denial_reason       VARCHAR(40),
    provider_error_code INTEGER,
    provider_message    VARCHAR(500),
    latency_ms          INTEGER,
    client_ip           INET,
    user_agent          VARCHAR(512),
    occurred_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_lock_access_log PRIMARY KEY (id),
    CONSTRAINT fk_lock_access_log_lock FOREIGN KEY (lock_device_id) REFERENCES lock_device (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lock_access_log_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lock_access_log_guest_access FOREIGN KEY (guest_access_id) REFERENCES guest_access_token (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lock_access_log_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_lock_access_log_actor CHECK (actor_type IN ('GUEST', 'STAFF', 'SYSTEM')),
    CONSTRAINT ck_lock_access_log_action CHECK (action IN
                                                ('REMOTE_UNLOCK', 'PASSCODE_CREATE', 'PASSCODE_DELETE', 'LOCK_SYNC')),
    CONSTRAINT ck_lock_access_log_result CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT ck_lock_access_log_denial CHECK (
        (result = 'DENIED' AND denial_reason IS NOT NULL) OR (result <> 'DENIED')),
    CONSTRAINT ck_lock_access_log_denial_reason CHECK (denial_reason IS NULL OR denial_reason IN
                                                       ('RESERVATION_NOT_CHECKED_IN', 'OUTSIDE_STAY_WINDOW', 'TOKEN_REVOKED',
                                                        'LOCK_NOT_ASSIGNED', 'LOCK_INACTIVE', 'RATE_LIMITED', 'NOT_AUTHORIZED')),
    CONSTRAINT ck_lock_access_log_actor_ref CHECK (
        (actor_type = 'GUEST' AND reservation_id IS NOT NULL) OR
        (actor_type = 'STAFF' AND user_id IS NOT NULL) OR
        (actor_type = 'SYSTEM'))
);
CREATE INDEX ix_lock_access_log_lock_time ON lock_access_log (lock_device_id, occurred_at DESC);
CREATE INDEX ix_lock_access_log_reservation ON lock_access_log (reservation_id, occurred_at DESC);
CREATE INDEX ix_lock_access_log_time ON lock_access_log (occurred_at DESC);
CREATE TRIGGER lock_access_log_append_only
    BEFORE UPDATE OR DELETE
    ON lock_access_log
    FOR EACH ROW
EXECUTE FUNCTION trg_forbid_modification();

-- =====================================================================================
-- 9. GOBERNANZA / LIMPIEZA
-- =====================================================================================
CREATE TABLE housekeeping_task
(
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    room_id             UUID        NOT NULL,
    reservation_id      UUID,
    task_type           VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    priority            SMALLINT    NOT NULL DEFAULT 3,
    scheduled_date      DATE        NOT NULL,
    due_by              TIMESTAMPTZ,
    assigned_user_id    UUID,
    notes               TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    verified_at         TIMESTAMPTZ,
    verified_by_user_id UUID,
    created_by_user_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_housekeeping_task PRIMARY KEY (id),
    CONSTRAINT fk_hk_task_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE CASCADE,
    CONSTRAINT fk_hk_task_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE SET NULL,
    CONSTRAINT fk_hk_task_assigned FOREIGN KEY (assigned_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_hk_task_verified_by FOREIGN KEY (verified_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_hk_task_created_by FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_hk_task_type CHECK (task_type IN
                                      ('CHECKOUT_CLEAN', 'STAYOVER_CLEAN', 'DEEP_CLEAN', 'INSPECTION', 'MAINTENANCE', 'LINEN_CHANGE')),
    CONSTRAINT ck_hk_task_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'VERIFIED', 'CANCELLED')),
    CONSTRAINT ck_hk_task_priority CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT ck_hk_task_done CHECK (status NOT IN ('DONE', 'VERIFIED') OR completed_at IS NOT NULL),
    CONSTRAINT ck_hk_task_verified CHECK (status <> 'VERIFIED' OR verified_at IS NOT NULL)
);
CREATE INDEX ix_hk_task_date_status ON housekeeping_task (scheduled_date, status);
CREATE INDEX ix_hk_task_room ON housekeeping_task (room_id);
CREATE INDEX ix_hk_task_assigned ON housekeeping_task (assigned_user_id) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE TRIGGER housekeeping_task_updated_at
    BEFORE UPDATE
    ON housekeeping_task
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- =====================================================================================
-- 10. PARTES DE VIAJEROS · SES.HOSPEDAJES (RD 933/2021)
-- =====================================================================================
-- Cada comunicación (alta de reserva, parte de entrada, modificación o anulación) con su
-- instantánea de datos, para conservar la trazabilidad exigida aunque cambie la ficha del huésped.
CREATE TABLE traveler_report
(
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    reservation_id         UUID        NOT NULL,
    communication_type     VARCHAR(20) NOT NULL,
    operation              VARCHAR(10) NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    establishment_code     VARCHAR(20) NOT NULL,
    contract_reference     VARCHAR(50) NOT NULL,
    contract_date          DATE        NOT NULL,
    check_in_at            TIMESTAMPTZ NOT NULL,
    check_out_at           TIMESTAMPTZ NOT NULL,
    num_travelers          SMALLINT    NOT NULL,
    num_rooms              SMALLINT    NOT NULL DEFAULT 1,
    internet_included      BOOLEAN     NOT NULL DEFAULT TRUE,
    payment_type           VARCHAR(10),
    payment_holder         VARCHAR(200),
    payment_identification VARCHAR(50),
    payment_expiry         VARCHAR(7),
    payment_date           DATE,
    deadline_at            TIMESTAMPTZ NOT NULL,
    ses_batch_id           VARCHAR(50),
    ses_communication_code VARCHAR(50),
    request_payload        TEXT,
    response_payload       TEXT,
    error_code             VARCHAR(50),
    error_message          VARCHAR(1000),
    attempts               INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMPTZ,
    submitted_at           TIMESTAMPTZ,
    accepted_at            TIMESTAMPTZ,
    created_by_user_id     UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_traveler_report PRIMARY KEY (id),
    CONSTRAINT fk_traveler_report_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_traveler_report_user FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    -- RESERVATION: comunicación de reserva; TRAVELER_ENTRY: parte de entrada de viajeros.
    CONSTRAINT ck_traveler_report_type CHECK (communication_type IN ('RESERVATION', 'TRAVELER_ENTRY')),
    -- A: alta, M: modificación, B: baja/anulación
    CONSTRAINT ck_traveler_report_operation CHECK (operation IN ('A', 'M', 'B')),
    CONSTRAINT ck_traveler_report_status CHECK (status IN
                                                ('DRAFT', 'PENDING', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'ERROR', 'CANCELLED')),
    CONSTRAINT ck_traveler_report_dates CHECK (check_out_at > check_in_at),
    CONSTRAINT ck_traveler_report_counts CHECK (num_travelers >= 1 AND num_rooms >= 1),
    -- Tipos de pago SES.HOSPEDAJES
    CONSTRAINT ck_traveler_report_payment CHECK (payment_type IS NULL OR payment_type IN
                                                 ('EFECT', 'TARJT', 'PLATF', 'TRANS', 'MOVIL', 'TREG', 'DESTI', 'OTRO')),
    CONSTRAINT ck_traveler_report_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_traveler_report_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL)
);
CREATE INDEX ix_traveler_report_reservation ON traveler_report (reservation_id);
CREATE INDEX ix_traveler_report_pending ON traveler_report (status, next_attempt_at)
    WHERE status IN ('PENDING', 'ERROR');
CREATE INDEX ix_traveler_report_deadline ON traveler_report (deadline_at) WHERE status NOT IN ('ACCEPTED', 'CANCELLED');
CREATE TRIGGER traveler_report_updated_at
    BEFORE UPDATE
    ON traveler_report
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

CREATE TABLE traveler_report_person
(
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    report_id               UUID         NOT NULL,
    guest_id                UUID,
    role                    VARCHAR(2)   NOT NULL,
    first_name              VARCHAR(100) NOT NULL,
    last_name1              VARCHAR(100) NOT NULL,
    last_name2              VARCHAR(100),
    sex                     CHAR(1)      NOT NULL,
    birth_date              DATE         NOT NULL,
    nationality             CHAR(3)      NOT NULL,
    document_type           VARCHAR(5),
    document_number         VARCHAR(30),
    document_support_number VARCHAR(30),
    document_issue_date     DATE,
    address_line            VARCHAR(250) NOT NULL,
    address_complement      VARCHAR(250),
    postal_code             VARCHAR(20)  NOT NULL,
    city                    VARCHAR(100),
    municipality_code       CHAR(5),
    country_code            CHAR(3)      NOT NULL,
    phone                   VARCHAR(30),
    phone_landline          VARCHAR(30),
    email                   VARCHAR(254),
    relationship            VARCHAR(2),
    CONSTRAINT pk_traveler_report_person PRIMARY KEY (id),
    CONSTRAINT fk_trp_report FOREIGN KEY (report_id) REFERENCES traveler_report (id) ON DELETE CASCADE,
    CONSTRAINT fk_trp_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE SET NULL,
    -- TI: titular del contrato; VI: viajero
    CONSTRAINT ck_trp_role CHECK (role IN ('TI', 'VI')),
    CONSTRAINT ck_trp_sex CHECK (sex IN ('H', 'M', 'O')),
    CONSTRAINT ck_trp_document_type CHECK (document_type IS NULL OR document_type IN ('NIF', 'NIE', 'PAS', 'OTRO')),
    CONSTRAINT ck_trp_document_pair CHECK ((document_type IS NULL) = (document_number IS NULL)),
    -- Para NIF y NIE el número de soporte es obligatorio.
    CONSTRAINT ck_trp_support_number CHECK (document_type NOT IN ('NIF', 'NIE') OR document_support_number IS NOT NULL),
    -- Para NIF el segundo apellido es obligatorio.
    CONSTRAINT ck_trp_last_name2 CHECK (document_type IS DISTINCT FROM 'NIF' OR last_name2 IS NOT NULL),
    CONSTRAINT ck_trp_nationality CHECK (nationality ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_trp_country CHECK (country_code ~ '^[A-Z]{3}$'),
    -- Residentes en España: código de municipio INE obligatorio.
    CONSTRAINT ck_trp_municipality CHECK (country_code <> 'ESP' OR municipality_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_trp_contact CHECK (phone IS NOT NULL OR phone_landline IS NOT NULL OR email IS NOT NULL),
    CONSTRAINT ck_trp_relationship CHECK (relationship IS NULL OR relationship IN
                                          ('AB', 'BA', 'BN', 'CD', 'CY', 'HJ', 'HR', 'NI', 'PM', 'SB', 'SG', 'TI', 'TU', 'YN', 'OT'))
);
CREATE INDEX ix_trp_report ON traveler_report_person (report_id);
CREATE INDEX ix_trp_guest ON traveler_report_person (guest_id);

-- =====================================================================================
-- 11. INTEGRACIONES (TTLock, Beds24, Stripe): credenciales OAuth, webhooks y outbox
-- =====================================================================================
-- Tokens OAuth de proveedores (TTLock /oauth2/token, Beds24 refresh token). Los valores se
-- almacenan cifrados por la aplicación (AES-GCM); la columna contiene el texto cifrado.
CREATE TABLE integration_credential
(
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    provider                VARCHAR(20) NOT NULL,
    access_token_encrypted  TEXT,
    refresh_token_encrypted TEXT,
    access_token_expires_at TIMESTAMPTZ,
    refresh_token_expires_at TIMESTAMPTZ,
    scope                   VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_credential PRIMARY KEY (id),
    CONSTRAINT uq_integration_credential_provider UNIQUE (provider),
    CONSTRAINT ck_integration_credential_provider CHECK (provider IN ('TTLOCK', 'BEDS24'))
);
CREATE TRIGGER integration_credential_updated_at
    BEFORE UPDATE
    ON integration_credential
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- Webhooks entrantes con idempotencia por (proveedor, id de evento).
CREATE TABLE inbound_webhook_event
(
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    provider          VARCHAR(20)  NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    attempts          INTEGER      NOT NULL DEFAULT 0,
    error_message     VARCHAR(2000),
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    CONSTRAINT pk_inbound_webhook_event PRIMARY KEY (id),
    CONSTRAINT uq_inbound_webhook_event UNIQUE (provider, external_event_id),
    CONSTRAINT ck_inbound_webhook_provider CHECK (provider IN ('STRIPE', 'BEDS24', 'TTLOCK')),
    CONSTRAINT ck_inbound_webhook_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    CONSTRAINT ck_inbound_webhook_attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_inbound_webhook_pending ON inbound_webhook_event (received_at) WHERE status IN ('RECEIVED', 'FAILED');

-- Patrón Transactional Outbox: eventos de dominio a publicar de forma fiable
-- (push de disponibilidad/tarifas a Beds24, envío de emails, partes SES, códigos TTLock...).
CREATE TABLE outbox_event
(
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    destination     VARCHAR(30)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(2000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_destination CHECK (destination IN ('BEDS24', 'EMAIL', 'SES_HOSPEDAJES', 'TTLOCK', 'GOOGLE_HOTEL')),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_outbox_event_pending ON outbox_event (next_attempt_at) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_outbox_event_aggregate ON outbox_event (aggregate_type, aggregate_id);

-- =====================================================================================
-- 12. AUDITORÍA GENERAL DEL BACKOFFICE (append-only)
-- =====================================================================================
CREATE TABLE audit_log
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id     UUID,
    actor_type  VARCHAR(10)  NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   VARCHAR(100),
    details     JSONB,
    client_ip   INET,
    user_agent  VARCHAR(512),
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_log_actor CHECK (actor_type IN ('STAFF', 'GUEST', 'SYSTEM', 'WEBHOOK'))
);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_log_user ON audit_log (user_id, occurred_at DESC);
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE
    ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION trg_forbid_modification();
