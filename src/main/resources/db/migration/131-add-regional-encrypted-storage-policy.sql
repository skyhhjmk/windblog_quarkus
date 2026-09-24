--liquibase formatted sql

--changeset windblog:131-add-regional-encrypted-storage-policy
ALTER TABLE storage_class
    ADD COLUMN excluded_content_regions JSONB,
    ADD COLUMN allow_encrypted_backup BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE storage_class
    ADD CONSTRAINT storage_class_excluded_regions_array
    CHECK (excluded_content_regions IS NULL OR jsonb_typeof(excluded_content_regions) = 'array');

COMMENT ON COLUMN storage_class.excluded_content_regions IS '媒体可见区域与此列表相交时禁止普通副本';
COMMENT ON COLUMN storage_class.allow_encrypted_backup IS '排异媒体允许以加密格式保存在私有备份目录';

--rollback ALTER TABLE storage_class DROP COLUMN allow_encrypted_backup, DROP COLUMN excluded_content_regions;
