--liquibase formatted sql

--changeset windblog:142-add-link-keyword-fraud-status
ALTER TABLE links
    ADD COLUMN keyword_fraud_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE links
    ADD CONSTRAINT links_keyword_fraud_status_check
        CHECK (keyword_fraud_status IN ('UNKNOWN', 'CLEAN', 'DETECTED'));
