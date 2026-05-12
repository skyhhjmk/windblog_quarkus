-- ============================================
-- 多区域可见性预留字段 (Phase 3 使用)
-- ============================================
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS visibility_regions JSONB;
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS visibility_regions JSONB;

COMMENT ON COLUMN media.visibility_regions IS '区域可见性预留字段(JSON数组)，Phase 3 使用';
COMMENT ON COLUMN posts.visibility_regions IS '区域可见性预留字段(JSON数组)，Phase 3 使用';
