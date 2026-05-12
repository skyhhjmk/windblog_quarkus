-- ============================================
-- 图像处理参数配置表
-- ============================================
CREATE TABLE image_processing_config
(
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT         NOT NULL,
    description  VARCHAR(500)          DEFAULT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO image_processing_config (config_key, config_value, description)
VALUES ('placeholder_max_width', '360', '占位图最大宽度(px)'),
       ('placeholder_quality', '0.6', '占位图JPEG质量(0.0-1.0)'),
       ('placeholder_format', 'jpeg', '占位图输出格式'),
       ('webp_quality', '0.82', 'WebP转换质量(0.0-1.0)'),
       ('webp_method', '4', 'WebP压缩方法(0-6, 0最快6最慢质量最好)'),
       ('cwebp_path', '/usr/bin/cwebp', 'cwebp命令行工具路径'),
       ('ffmpeg_path', '/usr/bin/ffmpeg', 'FFmpeg命令行工具路径');

COMMENT ON TABLE image_processing_config IS '图像/视频处理全局参数配置';
