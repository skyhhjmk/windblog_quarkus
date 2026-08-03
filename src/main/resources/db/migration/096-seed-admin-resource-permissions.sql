-- Normal administrator capabilities are explicit resource scopes. High-risk
-- resources remain super-admin-only in AdminAuthorizationPolicy.
INSERT INTO admin_role_permissions (role_name, permission, enabled)
VALUES
    ('ADMIN', 'auth.*', TRUE),
    ('ADMIN', 'audit_logs.*', TRUE),
    ('ADMIN', 'base.*', TRUE),
    ('ADMIN', 'post.*', TRUE),
    ('ADMIN', 'media.*', TRUE),
    ('ADMIN', 'tag.*', TRUE),
    ('ADMIN', 'category.*', TRUE),
    ('ADMIN', 'comment.*', TRUE),
    ('ADMIN', 'link.*', TRUE),
    ('ADMIN', 'region.*', TRUE),
    ('ADMIN', 'user.*', TRUE),
    ('ADMIN', 'wallet.*', TRUE),
    ('ADMIN', 'storage.*', TRUE),
    ('ADMIN', 'email_channels.*', TRUE),
    ('ADMIN', 'email_campaigns.*', TRUE),
    ('ADMIN', 'email_routing.*', TRUE),
    ('ADMIN', 'email_templates.*', TRUE),
    ('ADMIN', 'email_deliveries.*', TRUE),
    ('ADMIN', 'ai.*', TRUE),
    ('ADMIN', 'elasticsearch.*', TRUE),
    ('ADMIN', 'repost.*', TRUE),
    ('ADMIN', 'image_processing.*', TRUE),
    ('ADMIN', 'queue.read', TRUE),
    ('ADMIN', 'queue.publish', TRUE),
    ('ADMIN', 'edge.read', TRUE),
    ('ADMIN', 'dead_letter.read', TRUE),
    ('ADMIN', 'permissions.read', TRUE),
    ('ADMIN', 'settings.read', TRUE),
    ('ADMIN', 'media.download_audit.read', TRUE)
ON CONFLICT (role_name, permission) DO NOTHING;
