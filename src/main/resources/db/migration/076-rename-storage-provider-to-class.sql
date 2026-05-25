-- ============================================
-- 统一“存储类”和“区域”概念
-- ============================================

ALTER TABLE IF EXISTS storage_provider
    RENAME TO storage_class;

ALTER INDEX IF EXISTS idx_storage_provider_enabled
    RENAME TO idx_storage_class_enabled;

ALTER INDEX IF EXISTS idx_storage_provider_role
    RENAME TO idx_storage_class_role;

ALTER TABLE storage_class
    RENAME COLUMN region TO service_region;

ALTER TABLE media
    RENAME COLUMN storage_providers TO storage_classes;

ALTER TABLE media
    ADD COLUMN IF NOT EXISTS hidden_regions JSONB,
    ADD COLUMN IF NOT EXISTS sync_storage_classes JSONB,
    ADD COLUMN IF NOT EXISTS skip_storage_classes JSONB;

COMMENT ON TABLE storage_class IS '存储类配置表，表示本地目录、对象存储 Bucket、归档存储等媒体副本目标';
COMMENT ON COLUMN storage_class.service_region IS '存储服务物理区域，例如 OSS region，不参与内容区域可见性判断';
COMMENT ON COLUMN media.storage_classes IS '按存储类->变体嵌套的 JSONB 状态矩阵';
COMMENT ON COLUMN media.visibility_regions IS '媒体可见区域白名单，空值表示不限制';
COMMENT ON COLUMN media.hidden_regions IS '媒体不可见区域黑名单，优先级高于可见区域白名单';
COMMENT ON COLUMN media.sync_storage_classes IS '媒体指定同步的存储类列表，空值表示同步所有启用的非主存储类';
COMMENT ON COLUMN media.skip_storage_classes IS '媒体指定不同步的存储类列表，优先级高于同步列表';
