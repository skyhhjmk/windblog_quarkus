-- Add certificate columns to edge_nodes table for mTLS support
ALTER TABLE edge_nodes
    ADD COLUMN certificate_serial VARCHAR(200) UNIQUE;

ALTER TABLE edge_nodes
    ADD COLUMN certificate_expiry TIMESTAMP;

ALTER TABLE edge_nodes
    ADD COLUMN certificate_revoked BOOLEAN NOT NULL DEFAULT FALSE;
