-- liquibase formatted sql
-- changeset biliwind:046-create-temp-data-table

CREATE TABLE temp_data
(
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(64)  NOT NULL,
    target      VARCHAR(128) NOT NULL,
    payload     JSONB        NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'pending',
    retry_count INTEGER      NOT NULL DEFAULT 0,
    max_retries INTEGER      NOT NULL DEFAULT 3,
    error_msg   TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_temp_data_type_status ON temp_data (type, status);
CREATE INDEX idx_temp_data_created_at ON temp_data (created_at);

COMMENT ON COLUMN temp_data.type IS '数据类型标识，例如 es_sync_task, ai_task 等';
COMMENT ON COLUMN temp_data.target IS '目标标识，用于快速定位特定任务';
COMMENT ON COLUMN temp_data.payload IS '临时数据内容，JSONB 格式存储';
COMMENT ON COLUMN temp_data.status IS '状态：pending=待处理, processing=处理中, completed=已完成, failed=已失败';

-- rollback DROP TABLE temp_data;
