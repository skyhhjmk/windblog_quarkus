--liquibase formatted sql

--changeset biliwind:081-add-link-application-and-distributed-monitoring
ALTER TABLE links
    ADD COLUMN application_status  SMALLINT    NOT NULL DEFAULT 1,
    ADD COLUMN availability_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN backlink_status     VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN last_checked_at     TIMESTAMPTZ;

ALTER TABLE link_monitor_log
    ADD COLUMN check_batch_id VARCHAR(64),
    ADD COLUMN node_id        VARCHAR(128) NOT NULL DEFAULT 'main',
    ADD COLUMN node_name      VARCHAR(255),
    ADD COLUMN error_message  TEXT;

ALTER TABLE links
    ADD CONSTRAINT chk_links_application_status
        CHECK (application_status IN (1, 2, 3));

ALTER TABLE links
    ADD CONSTRAINT chk_links_availability_status
        CHECK (availability_status IN ('UNKNOWN', 'ONLINE', 'OFFLINE'));

ALTER TABLE links
    ADD CONSTRAINT chk_links_backlink_status
        CHECK (backlink_status IN ('UNKNOWN', 'FOUND', 'MISSING'));

CREATE INDEX idx_links_application_status
    ON links (application_status, created_at DESC);

CREATE INDEX idx_link_monitor_batch
    ON link_monitor_log (check_batch_id, node_id);

COMMENT ON COLUMN links.application_status IS '申请状态：1=已通过，2=待审核，3=已拒绝';
COMMENT ON COLUMN links.availability_status IS '多节点聚合可用状态';
COMMENT ON COLUMN links.backlink_status IS '最近一次成功访问结果中的反链状态';
COMMENT ON COLUMN links.last_checked_at IS '最近一次多节点监控完成时间';
COMMENT ON COLUMN link_monitor_log.check_batch_id IS '同一轮多节点检测批次ID';
COMMENT ON COLUMN link_monitor_log.node_id IS '执行检测的节点ID';
COMMENT ON COLUMN link_monitor_log.node_name IS '执行检测的节点名称';
COMMENT ON COLUMN link_monitor_log.error_message IS '检测失败原因';

--rollback DROP INDEX IF EXISTS idx_link_monitor_batch;
--rollback DROP INDEX IF EXISTS idx_links_application_status;
--rollback ALTER TABLE link_monitor_log DROP COLUMN IF EXISTS error_message;
--rollback ALTER TABLE link_monitor_log DROP COLUMN IF EXISTS node_name;
--rollback ALTER TABLE link_monitor_log DROP COLUMN IF EXISTS node_id;
--rollback ALTER TABLE link_monitor_log DROP COLUMN IF EXISTS check_batch_id;
--rollback ALTER TABLE links DROP CONSTRAINT IF EXISTS chk_links_backlink_status;
--rollback ALTER TABLE links DROP CONSTRAINT IF EXISTS chk_links_availability_status;
--rollback ALTER TABLE links DROP CONSTRAINT IF EXISTS chk_links_application_status;
--rollback ALTER TABLE links DROP COLUMN IF EXISTS last_checked_at;
--rollback ALTER TABLE links DROP COLUMN IF EXISTS backlink_status;
--rollback ALTER TABLE links DROP COLUMN IF EXISTS availability_status;
--rollback ALTER TABLE links DROP COLUMN IF EXISTS application_status;
