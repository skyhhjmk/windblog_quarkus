INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES
    ('ADMIN', 'system.*', TRUE),
    ('ADMIN', 'store.*', TRUE),
    ('ADMIN', 'queue.write', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;
