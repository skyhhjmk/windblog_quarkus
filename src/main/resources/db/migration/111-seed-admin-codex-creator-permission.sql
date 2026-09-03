-- Codex Creator status and redacted connection metadata are safe to inspect;
-- connection writes remain super-admin-only in AdminAuthorizationPolicy.
INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES ('ADMIN', 'codex_creator.read', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;
