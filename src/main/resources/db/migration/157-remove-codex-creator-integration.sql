-- liquibase formatted sql

-- changeset windblog:157-remove-codex-creator-integration
-- The external Codex Creator service is retired. Every AI operation is served
-- by a configured AI provider or polling group, so remove the internal
-- provider row and the dedicated admin permissions.
DELETE FROM ai_provider_configs WHERE UPPER(provider) = 'CODEX_CREATOR';
DELETE FROM admin_role_permissions
 WHERE permission IN ('codex_creator.read', 'codex_creator.write');

-- rollback INSERT INTO admin_role_permissions (role_name, permission, enabled)
--          VALUES ('ADMIN', 'codex_creator.read', TRUE), ('ADMIN', 'codex_creator.write', TRUE);
