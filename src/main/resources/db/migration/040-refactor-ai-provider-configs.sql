-- liquibase formatted sql

-- changeset biliwind:040-refactor-ai-provider-configs
-- 1. 添加新的字段
ALTER TABLE ai_provider_configs
    ADD COLUMN type   VARCHAR(32) NOT NULL DEFAULT 'PROVIDER',
    ADD COLUMN name   VARCHAR(64) NOT NULL DEFAULT 'Default',
    ADD COLUMN config JSONB;

-- 2. 为已有的记录设置名字（如果不叫默认或者需要基于 provider 设置）
UPDATE ai_provider_configs
SET name = CONCAT(provider, ' - ', id)
WHERE id > 0;

-- 3. 去掉对 provider 列的唯一约束，并加上 name 列的唯一约束
ALTER TABLE ai_provider_configs
    DROP CONSTRAINT ai_provider_configs_provider_key;
ALTER TABLE ai_provider_configs
    ADD CONSTRAINT ai_provider_configs_name_key UNIQUE (name);

