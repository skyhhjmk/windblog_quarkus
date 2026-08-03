INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES ('ADMIN', 'outbox.read', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;
