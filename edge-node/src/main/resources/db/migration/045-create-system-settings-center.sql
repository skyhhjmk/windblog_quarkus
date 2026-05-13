-- liquibase formatted sql
-- changeset biliwind:045-create-system-settings-center
CREATE TABLE system_settings
(
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL UNIQUE,
    config_value JSONB        NOT NULL,              -- 配置实际值
    config_type  VARCHAR(32)  NOT NULL DEFAULT 'string',
    group_name   VARCHAR(64)  NOT NULL DEFAULT 'general',
    ui_schema    JSONB        NOT NULL,              -- 核心：定义UI渲染逻辑和验证规则
    description  TEXT,
    version      INTEGER      NOT NULL DEFAULT 1,    -- 用于版本控制
    is_frozen    BOOLEAN      NOT NULL DEFAULT FALSE,-- 锁定状态（回滚验证期间设为TRUE）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE system_settings_history
(
    id            BIGSERIAL PRIMARY KEY,
    setting_id    BIGINT       NOT NULL,
    config_key    VARCHAR(128) NOT NULL,
    config_value  JSONB        NOT NULL,
    version       INTEGER      NOT NULL,
    operator_id   BIGINT, -- 记录操作者ID
    change_reason TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 添加索引
CREATE INDEX idx_settings_group ON system_settings (group_name);
CREATE INDEX idx_settings_history_key ON system_settings_history (config_key);

-- 字段注释
COMMENT
    ON COLUMN system_settings.ui_schema IS 'UI协议，例如：{"widget":"input","rules":{"required":true}}';
COMMENT
    ON COLUMN system_settings.is_frozen IS '是否处于变更验证锁定期';

-- 插入示例：站点基本设置
INSERT INTO system_settings (config_key, config_value, group_name, ui_schema, description)
VALUES ('site_info',
        '{
          "title": "WindBlog",
          "keywords": "blog, tech"
        }',
        'basic',
        '{
          "type": "object",
          "fields": [
            {
              "key": "title",
              "label": "站点标题",
              "widget": "input",
              "required": true
            },
            {
              "key": "keywords",
              "label": "SEO关键词",
              "widget": "tag_input"
            }
          ]
        }',
        '网站基础信息设置');

-- rollback DROP TABLE system_settings_history;
-- rollback DROP TABLE system_settings;
