--liquibase formatted sql

--changeset windblog:113-seed-admin-codex-creator-write-permission
INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES ('ADMIN', 'codex_creator.write', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;

--rollback DELETE FROM admin_role_permissions WHERE role_name = 'ADMIN' AND permission = 'codex_creator.write';
