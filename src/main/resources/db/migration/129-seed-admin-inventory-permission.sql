-- changeset windblog:129-seed-admin-inventory-permission
INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES ('ADMIN', 'inventory.*', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;

-- rollback DELETE FROM admin_role_permissions WHERE role_name = 'ADMIN' AND permission = 'inventory.*';
