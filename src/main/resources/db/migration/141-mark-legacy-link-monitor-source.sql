--liquibase formatted sql

--changeset biliwind:141-mark-legacy-link-monitor-source
UPDATE link_monitor_log
SET check_source = 'UNKNOWN'
WHERE check_source = 'AUTOMATIC'
  AND (raw_data IS NULL OR NOT jsonb_exists(raw_data, 'checkSource'));

--rollback UPDATE link_monitor_log SET check_source = 'AUTOMATIC' WHERE check_source = 'UNKNOWN' AND (raw_data IS NULL OR NOT jsonb_exists(raw_data, 'checkSource'));
