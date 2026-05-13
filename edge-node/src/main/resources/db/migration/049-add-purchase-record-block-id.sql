-- liquibase formatted sql

-- changeset windblog:049-add-purchase-record-block-id

-- 为 user_purchase_records 表添加 target_block_id 字段，用于精确区块解锁
ALTER TABLE user_purchase_records
    ADD COLUMN target_block_id VARCHAR(64);
CREATE INDEX idx_user_purchase_records_target_block_id ON user_purchase_records (target_block_id);
