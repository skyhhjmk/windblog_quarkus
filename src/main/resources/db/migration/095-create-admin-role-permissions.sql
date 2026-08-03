CREATE TABLE IF NOT EXISTS admin_role_permissions (
    role_name VARCHAR(64) NOT NULL,
    permission VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_name, permission)
);

INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES
    ('ADMIN', 'admin.read', TRUE),
    ('ADMIN', 'admin.write', TRUE),
    ('ADMIN', 'admin.delete', TRUE),
    ('ADMIN', 'post.publish', TRUE),
    ('ADMIN', 'media.download_audit.read', TRUE),
    ('ADMIN', 'settings.read', TRUE),
    ('ADMIN', 'queue.read', TRUE),
    ('ADMIN', 'ai.read', TRUE),
    ('ADMIN', 'media.read', TRUE),
    ('ADMIN', 'media.write', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;

INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES ('SUPER_ADMIN', 'admin.*', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_admin_role_permissions_enabled
    ON admin_role_permissions (role_name, enabled);
