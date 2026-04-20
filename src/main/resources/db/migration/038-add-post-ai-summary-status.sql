-- liquibase formatted sql

-- changeset windblog:038-1
ALTER TABLE posts
    ADD COLUMN ai_summary_status SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN posts.ai_summary_status IS 'AI摘要状态：0-自动，1-冻结，2-关闭';
