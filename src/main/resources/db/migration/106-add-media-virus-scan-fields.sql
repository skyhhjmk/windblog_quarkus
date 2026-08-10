--liquibase formatted sql

--changeset biliwind:106-add-media-virus-scan-fields
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS virus_scan_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SCANNED';
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS virus_scanned_at TIMESTAMPTZ;
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS virus_scan_message TEXT;

COMMENT ON COLUMN media.virus_scan_status IS
    '媒体病毒扫描状态: NOT_SCANNED, SCANNING, CLEAN, INFECTED, UNAVAILABLE, DISABLED';
COMMENT ON COLUMN media.virus_scanned_at IS '媒体病毒扫描完成或尝试时间';
COMMENT ON COLUMN media.virus_scan_message IS '媒体病毒扫描结果或失败原因';

--rollback alter table media drop column if exists virus_scan_message;
--rollback alter table media drop column if exists virus_scanned_at;
--rollback alter table media drop column if exists virus_scan_status;
