--liquibase formatted sql

--changeset windblog:143-add-comment-visitor-details
ALTER TABLE comments ADD COLUMN guest_website VARCHAR(2048);
ALTER TABLE comments ADD COLUMN client_os VARCHAR(32);
ALTER TABLE comments ADD COLUMN client_browser VARCHAR(32);
