-- liquibase formatted sql

-- changeset biliwind:036-create-dead-letter-messages
-- comment: 创建死信消息表

-- 创建死信消息表
CREATE TABLE dead_letter_messages
(
    id               BIGINT PRIMARY KEY,
    source_queue     VARCHAR(255) NOT NULL,
    exchange_name    VARCHAR(255),
    routing_key      VARCHAR(255),
    post_id          BIGINT,
    priority         INTEGER,
    retry_count      INTEGER,
    error_reason     VARCHAR(1024),
    message_content  JSONB,
    dead_lettered_at TIMESTAMP    NOT NULL,
    is_processed     BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at     TIMESTAMP,
    process_note     VARCHAR(512),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP
);

-- 创建序列
CREATE SEQUENCE dead_letter_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- 设置默认值
ALTER TABLE dead_letter_messages
    ALTER COLUMN id SET DEFAULT nextval('dead_letter_messages_id_seq');

-- 创建索引
CREATE INDEX idx_dead_letter_post_id ON dead_letter_messages (post_id);
CREATE INDEX idx_dead_letter_is_processed ON dead_letter_messages (is_processed);
CREATE INDEX idx_dead_letter_source_queue ON dead_letter_messages (source_queue);
CREATE INDEX idx_dead_letter_dead_lettered_at ON dead_letter_messages (dead_lettered_at DESC);
CREATE INDEX idx_dead_letter_created_at ON dead_letter_messages (created_at DESC);

-- 添加注释
COMMENT ON TABLE dead_letter_messages IS '死信消息记录表';
COMMENT ON COLUMN dead_letter_messages.source_queue IS '消息来源队列名称';
COMMENT ON COLUMN dead_letter_messages.exchange_name IS '交换机名称';
COMMENT ON COLUMN dead_letter_messages.routing_key IS '路由键';
COMMENT ON COLUMN dead_letter_messages.post_id IS '文章 ID（AI 摘要任务）';
COMMENT ON COLUMN dead_letter_messages.priority IS '任务优先级';
COMMENT ON COLUMN dead_letter_messages.retry_count IS '重试次数';
COMMENT ON COLUMN dead_letter_messages.error_reason IS '错误原因';
COMMENT ON COLUMN dead_letter_messages.message_content IS '消息内容（JSON 格式）';
COMMENT ON COLUMN dead_letter_messages.dead_lettered_at IS '进入死信队列的时间';
COMMENT ON COLUMN dead_letter_messages.is_processed IS '是否已处理';
COMMENT ON COLUMN dead_letter_messages.processed_at IS '处理时间';
COMMENT ON COLUMN dead_letter_messages.process_note IS '处理备注';
