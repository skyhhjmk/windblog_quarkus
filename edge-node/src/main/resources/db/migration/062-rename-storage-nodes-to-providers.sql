-- ============================================
-- 将 media 表的 storage_nodes 字段重命名为 storage_providers
-- 以区分“存储类/提供者”与“边缘节点”的概念
-- ============================================
ALTER TABLE media
    RENAME COLUMN storage_nodes TO storage_providers;

COMMENT ON COLUMN media.storage_providers IS '按存储提供者(Provider)->变体(Variant)嵌套的JSONB状态矩阵';
