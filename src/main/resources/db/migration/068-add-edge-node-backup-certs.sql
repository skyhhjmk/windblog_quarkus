-- Add backup certificate fields to edge_nodes for smooth rotation
ALTER TABLE edge_nodes
    ADD COLUMN certificate_backup_serial VARCHAR(200);
ALTER TABLE edge_nodes
    ADD COLUMN certificate_backup_expiry TIMESTAMP WITH TIME ZONE;
ALTER TABLE edge_nodes
    ADD COLUMN is_trusted BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN edge_nodes.certificate_backup_serial IS '备用证书序列号';
COMMENT ON COLUMN edge_nodes.certificate_backup_expiry IS '备用证书过期时间';
COMMENT ON COLUMN edge_nodes.is_trusted IS '节点是否已通过 mTLS 认证并被信任';
