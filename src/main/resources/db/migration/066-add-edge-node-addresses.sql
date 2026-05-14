-- 添加边缘节点多地址字段
-- external_url: 外部访问地址（用户浏览器访问）
-- api_url: API 通信地址（后端服务间通信）
-- grpc_address: gRPC 通信地址（主节点向从节点推送数据）

ALTER TABLE edge_nodes
    ADD COLUMN IF NOT EXISTS external_url VARCHAR(255);

ALTER TABLE edge_nodes
    ADD COLUMN IF NOT EXISTS api_url VARCHAR(255);

ALTER TABLE edge_nodes
    ADD COLUMN IF NOT EXISTS grpc_address VARCHAR(255);

-- 将现有 address 数据迁移到 grpc_address（保持向后兼容）
UPDATE edge_nodes
SET grpc_address = address
WHERE grpc_address IS NULL
  AND address IS NOT NULL;

-- 添加注释
COMMENT ON COLUMN edge_nodes.external_url IS '外部访问地址（用户浏览器访问的 URL）';
COMMENT ON COLUMN edge_nodes.api_url IS 'API 通信地址（通常与外部访问地址一致）';
COMMENT ON COLUMN edge_nodes.grpc_address IS 'gRPC 通信地址（主节点向从节点推送数据使用）';
