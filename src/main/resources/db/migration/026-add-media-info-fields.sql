-- liquibase formatted sql

-- changeset biliwind:1771742726558-14
ALTER TABLE media
    ADD file_name VARCHAR(255);
ALTER TABLE media
    ADD uploaded_by BIGINT;

