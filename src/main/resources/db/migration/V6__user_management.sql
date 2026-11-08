-- =====================================================================================
-- V6 · Gestión de usuarios del backoffice
--   * password_change_required: la contraseña es temporal (alta o reseteo por un administrador) y debe
--     cambiarse antes de usar el backoffice.
--   * sessions_revoked_at: los access tokens emitidos antes de este instante dejan de ser válidos
--     (baja, cambio de rol o de contraseña), sin esperar a que caduquen.
-- =====================================================================================
ALTER TABLE app_user
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sessions_revoked_at      TIMESTAMPTZ;

CREATE INDEX ix_app_user_role_enabled ON app_user (role, enabled);
