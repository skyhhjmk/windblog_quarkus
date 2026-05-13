-- ============================================
-- media 表新增处理状态字段
-- ============================================
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS processing_status VARCHAR(50) DEFAULT 'COMPLETED';
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS processing_progress INT DEFAULT 100;
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS processing_error TEXT;

COMMENT ON COLUMN media.processing_status IS '媒体处理状态: PENDING, PROCESSING, COMPLETED, FAILED';
COMMENT ON COLUMN media.processing_progress IS '处理进度百分比 (0-100)';
COMMENT ON COLUMN media.processing_error IS '处理失败时的详细错误信息';
