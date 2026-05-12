-- ============================================
-- 存储提供者配置表
-- ============================================
CREATE TABLE storage_provider
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    provider_type   VARCHAR(20)  NOT NULL,
    is_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_primary      BOOLEAN      NOT NULL DEFAULT FALSE,
    role            VARCHAR(20)  NOT NULL DEFAULT 'backup',
    config_json     JSONB        NOT NULL DEFAULT '{}',
    supported_types JSONB        NOT NULL DEFAULT '["*"]',
    cdn_domain      VARCHAR(255)          DEFAULT NULL,
    cdn_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    region          VARCHAR(20)  NOT NULL DEFAULT 'global',
    priority        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_storage_provider_enabled ON storage_provider (is_enabled);
CREATE INDEX idx_storage_provider_role ON storage_provider (role);

COMMENT ON TABLE storage_provider IS '存储提供者配置表，支持多种对象存储后端';
COMMENT ON COLUMN storage_provider.name IS '唯一标识符，如 aliyun、local';
COMMENT ON COLUMN storage_provider.provider_type IS '存储类型: oss_aliyun, local_fs';
COMMENT ON COLUMN storage_provider.config_json IS '连接配置JSON，敏感字段可用${env:XXX}引用环境变量';
COMMENT ON COLUMN storage_provider.supported_types IS '支持的MIME类型列表JSON数组';
