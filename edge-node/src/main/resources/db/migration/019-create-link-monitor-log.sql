--liquibase formatted sql


--changeset biliwind:006-create-link-monitor-log-table
CREATE TABLE link_monitor_log
(
    id             BIGSERIAL PRIMARY KEY,
    link_id        BIGINT      NOT NULL REFERENCES links (id) ON DELETE CASCADE,

    check_time     TIMESTAMPTZ NOT NULL,
    ok             BOOLEAN,
    load_time_ms   INT,
    backlink_found BOOLEAN,
    status_code    INT,
    raw_data       JSONB
);

COMMENT ON TABLE link_monitor_log IS '友链监控日志表，用于记录可用性检测结果';

COMMENT ON COLUMN link_monitor_log.id IS '主键ID';
COMMENT ON COLUMN link_monitor_log.link_id IS '关联的链接ID';
COMMENT ON COLUMN link_monitor_log.check_time IS '检测时间';
COMMENT ON COLUMN link_monitor_log.ok IS '是否访问成功';
COMMENT ON COLUMN link_monitor_log.load_time_ms IS '页面加载耗时（毫秒）';
COMMENT ON COLUMN link_monitor_log.backlink_found IS '是否检测到反向友链';
COMMENT ON COLUMN link_monitor_log.status_code IS 'HTTP响应状态码';
COMMENT ON COLUMN link_monitor_log.raw_data IS '原始检测数据(JSON格式)';


--changeset biliwind:007-link-monitor-indexes
CREATE INDEX idx_monitor_link_time
    ON link_monitor_log (link_id, check_time DESC);

--rollback DROP INDEX IF EXISTS idx_monitor_link_time;
--rollback DROP TABLE IF EXISTS link_monitor_log;
