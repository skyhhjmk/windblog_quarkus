-- Add certificate serial number and expiry fields to edge_nodes
ALTER TABLE edge_nodes
    ADD COLUMN certificate_serial VARCHAR(200);
ALTER TABLE edge_nodes
    ADD COLUMN certificate_expiry TIMESTAMP WITH TIME ZONE;
ALTER TABLE edge_nodes
    ADD COLUMN certificate_revoked BOOLEAN DEFAULT FALSE;

-- Add unique constraint for certificate serial
ALTER TABLE edge_nodes
    ADD CONSTRAINT uk_edge_node_cert_serial UNIQUE (certificate_serial);

COMMENT ON COLUMN edge_nodes.certificate_serial IS '证书序列号（唯一）';
COMMENT ON COLUMN edge_nodes.certificate_expiry IS '证书过期时间';
COMMENT ON COLUMN edge_nodes.certificate_revoked IS '证书是否已吊销';
