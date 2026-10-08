--liquibase formatted sql

--changeset biliwind:140-link-monitor-source-and-retention
ALTER TABLE link_monitor_log
    ADD COLUMN check_source VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC';

COMMENT ON COLUMN link_monitor_log.check_source IS '检测来源：AUTOMATIC 自动检测，MANUAL 手动检测';

DELETE FROM link_monitor_log
WHERE check_time < CURRENT_TIMESTAMP - INTERVAL '90 days';

CREATE INDEX idx_link_monitor_check_time
    ON link_monitor_log (check_time);

--rollback DROP INDEX IF EXISTS idx_link_monitor_check_time;
--rollback ALTER TABLE link_monitor_log DROP COLUMN IF EXISTS check_source;
