-- Create edge_nodes table
CREATE TABLE edge_nodes
(
    node_id         VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(100)             NOT NULL,
    address         VARCHAR(255),
    region          VARCHAR(20)              NOT NULL,
    connection_type VARCHAR(20)              NOT NULL DEFAULT 'HEARTBEAT',
    is_enabled      BOOLEAN                  NOT NULL DEFAULT TRUE,
    status          VARCHAR(20)              NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat  TIMESTAMP WITH TIME ZONE,
    metrics         JSONB,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Add comment
COMMENT ON TABLE edge_nodes IS '边缘节点管理表';
COMMENT ON COLUMN edge_nodes.node_id IS '节点唯一标识';
COMMENT ON COLUMN edge_nodes.name IS '节点名称';
COMMENT ON COLUMN edge_nodes.address IS '节点地址 (用于主动连接)';
COMMENT ON COLUMN edge_nodes.region IS '所属区域 (CHINA/GLOBAL)';
COMMENT ON COLUMN edge_nodes.connection_type IS '连接模式 (HEARTBEAT/ACTIVE_POLL)';
