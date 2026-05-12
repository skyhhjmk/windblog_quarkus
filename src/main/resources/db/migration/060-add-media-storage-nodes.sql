-- ============================================
-- media 表新增多存储节点追踪字段
-- ============================================
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS storage_nodes JSONB NOT NULL DEFAULT '{}';
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN media.storage_nodes IS '按节点->变体嵌套的JSONB状态矩阵，记录每个存储节点各变体的同步状态、路径、大小等';
COMMENT ON COLUMN media.version IS '乐观锁版本号，用于并发更新控制';

-- 为已有数据初始化空 storage_nodes（新上传的数据由应用层填充）
UPDATE media
SET storage_nodes = '{}',
    version       = 1
WHERE storage_nodes = '{}'
   OR storage_nodes IS NULL;
