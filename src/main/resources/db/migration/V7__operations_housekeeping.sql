-- =====================================================================================
-- Fase 10 · Centro de operaciones: limpieza con inspección, listas de comprobación con fotos,
-- incidencias y objetos olvidados.
--
-- Estados de limpieza de la unidad (room.housekeeping_status), sin cambiar los códigos:
--   DIRTY = pendiente de limpieza · IN_PROGRESS = en limpieza · CLEAN = limpio, pendiente de inspección
--   INSPECTED = LISTO para el huésped · OUT_OF_SERVICE = fuera de servicio
-- =====================================================================================

-- ---------------------------------------------------------------- ficheros (fotos)
-- El contenido vive en disco (app.media.storage-path); aquí solo los metadatos. Cada fichero pertenece a
-- un único registro (owner_type + owner_id) y se borra con él desde la aplicación.
CREATE TABLE media_file
(
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    owner_type          VARCHAR(30)  NOT NULL,
    owner_id            UUID         NOT NULL,
    content_type        VARCHAR(50)  NOT NULL,
    size_bytes          INTEGER      NOT NULL,
    sha256              VARCHAR(64)  NOT NULL,
    storage_key         VARCHAR(200) NOT NULL,
    uploaded_by_user_id UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_media_file PRIMARY KEY (id),
    CONSTRAINT uq_media_file_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_media_file_uploaded_by FOREIGN KEY (uploaded_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_media_file_owner CHECK (owner_type IN ('TASK_CHECK', 'INCIDENT', 'LOST_ITEM')),
    CONSTRAINT ck_media_file_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_media_file_size CHECK (size_bytes > 0)
);
CREATE INDEX ix_media_file_owner ON media_file (owner_type, owner_id);

-- ---------------------------------------------------------------- numeración de expedientes
-- INC-2026-0001, OBJ-2026-0001… Un contador por tipo y año, incrementado con UPSERT atómico.
CREATE TABLE ops_counter
(
    kind       VARCHAR(10) NOT NULL,
    year       SMALLINT    NOT NULL,
    last_value INTEGER     NOT NULL,
    CONSTRAINT pk_ops_counter PRIMARY KEY (kind, year),
    CONSTRAINT ck_ops_counter_value CHECK (last_value > 0)
);

-- ---------------------------------------------------------------- listas de comprobación
-- Una plantilla activa por tipo de apartamento y, opcionalmente, una general (room_type_id nulo) que se usa
-- cuando el tipo no tiene la suya.
CREATE TABLE checklist_template
(
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    room_type_id UUID,
    name         VARCHAR(120) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_checklist_template PRIMARY KEY (id),
    CONSTRAINT fk_checklist_template_room_type FOREIGN KEY (room_type_id) REFERENCES room_type (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_checklist_template_active
    ON checklist_template (coalesce(room_type_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE active;
CREATE TRIGGER checklist_template_updated_at
    BEFORE UPDATE
    ON checklist_template
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

CREATE TABLE checklist_item
(
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    template_id    UUID         NOT NULL,
    position       SMALLINT     NOT NULL,
    section        VARCHAR(60),
    label          VARCHAR(200) NOT NULL,
    photo_required BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_checklist_item PRIMARY KEY (id),
    CONSTRAINT fk_checklist_item_template FOREIGN KEY (template_id) REFERENCES checklist_template (id) ON DELETE CASCADE,
    -- Diferida: al reordenar, Hibernate inserta las filas nuevas antes de borrar las antiguas.
    CONSTRAINT uq_checklist_item_position UNIQUE (template_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_checklist_item_position CHECK (position >= 0)
);

-- ---------------------------------------------------------------- tareas: lista ejecutada e inspección
ALTER TABLE housekeeping_task
    ADD COLUMN checklist_template_id UUID,
    ADD COLUMN completed_by_user_id  UUID,
    ADD COLUMN rejection_count       SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN rejection_reason      VARCHAR(500),
    ADD COLUMN rejected_at           TIMESTAMPTZ,
    ADD COLUMN rejected_by_user_id   UUID,
    ADD CONSTRAINT fk_hk_task_checklist FOREIGN KEY (checklist_template_id) REFERENCES checklist_template (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_hk_task_completed_by FOREIGN KEY (completed_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_hk_task_rejected_by FOREIGN KEY (rejected_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_hk_task_rejections CHECK (rejection_count >= 0);

-- Copia de la plantilla tomada al empezar la tarea: editar la plantilla no altera limpiezas pasadas.
CREATE TABLE housekeeping_task_check
(
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    task_id            UUID         NOT NULL,
    position           SMALLINT     NOT NULL,
    section            VARCHAR(60),
    label              VARCHAR(200) NOT NULL,
    photo_required     BOOLEAN      NOT NULL,
    checked_at         TIMESTAMPTZ,
    checked_by_user_id UUID,
    comment            VARCHAR(500),
    CONSTRAINT pk_housekeeping_task_check PRIMARY KEY (id),
    CONSTRAINT fk_hk_check_task FOREIGN KEY (task_id) REFERENCES housekeeping_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_hk_check_user FOREIGN KEY (checked_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_hk_check_position UNIQUE (task_id, position)
);

-- ---------------------------------------------------------------- incidencias
-- Primera versión del centro de incidencias: la fase de mantenimiento ampliará estados, proveedores y costes.
CREATE TABLE incident
(
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    code                VARCHAR(20)  NOT NULL,
    room_id             UUID,
    task_id             UUID,
    title               VARCHAR(150) NOT NULL,
    description         TEXT,
    priority            VARCHAR(2)   NOT NULL,
    origin              VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    reported_by_user_id UUID,
    resolution          TEXT,
    resolved_at         TIMESTAMPTZ,
    resolved_by_user_id UUID,
    closed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_incident PRIMARY KEY (id),
    CONSTRAINT uq_incident_code UNIQUE (code),
    CONSTRAINT fk_incident_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE SET NULL,
    CONSTRAINT fk_incident_task FOREIGN KEY (task_id) REFERENCES housekeeping_task (id) ON DELETE SET NULL,
    CONSTRAINT fk_incident_reported_by FOREIGN KEY (reported_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_incident_resolved_by FOREIGN KEY (resolved_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_incident_priority CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_incident_origin CHECK (origin IN ('HOUSEKEEPING', 'STAFF', 'GUEST')),
    CONSTRAINT ck_incident_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_incident_resolved CHECK (status NOT IN ('RESOLVED', 'CLOSED') OR resolved_at IS NOT NULL)
);
CREATE INDEX ix_incident_open ON incident (priority, created_at) WHERE status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX ix_incident_room ON incident (room_id, created_at DESC);
CREATE INDEX ix_incident_task ON incident (task_id) WHERE task_id IS NOT NULL;
CREATE TRIGGER incident_updated_at
    BEFORE UPDATE
    ON incident
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();

-- ---------------------------------------------------------------- objetos olvidados
CREATE TABLE lost_item
(
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    code              VARCHAR(20)  NOT NULL,
    room_id           UUID,
    reservation_id    UUID,
    task_id           UUID,
    description       VARCHAR(300) NOT NULL,
    storage_location  VARCHAR(120),
    status            VARCHAR(20)  NOT NULL DEFAULT 'STORED',
    found_at          TIMESTAMPTZ  NOT NULL,
    found_by_user_id  UUID,
    guest_notified_at TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    notes             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_lost_item PRIMARY KEY (id),
    CONSTRAINT uq_lost_item_code UNIQUE (code),
    CONSTRAINT fk_lost_item_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE SET NULL,
    CONSTRAINT fk_lost_item_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE SET NULL,
    CONSTRAINT fk_lost_item_task FOREIGN KEY (task_id) REFERENCES housekeeping_task (id) ON DELETE SET NULL,
    CONSTRAINT fk_lost_item_found_by FOREIGN KEY (found_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_lost_item_status CHECK (status IN ('STORED', 'GUEST_NOTIFIED', 'SHIPPED', 'COLLECTED', 'DISCARDED')),
    CONSTRAINT ck_lost_item_closed CHECK (status NOT IN ('SHIPPED', 'COLLECTED', 'DISCARDED') OR closed_at IS NOT NULL)
);
CREATE INDEX ix_lost_item_open ON lost_item (found_at) WHERE status IN ('STORED', 'GUEST_NOTIFIED');
CREATE INDEX ix_lost_item_reservation ON lost_item (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE TRIGGER lost_item_updated_at
    BEFORE UPDATE
    ON lost_item
    FOR EACH ROW
EXECUTE FUNCTION trg_set_updated_at();
