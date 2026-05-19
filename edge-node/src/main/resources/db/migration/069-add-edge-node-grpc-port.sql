ALTER TABLE edge_nodes
    ADD COLUMN IF NOT EXISTS edge_grpc_port INTEGER;