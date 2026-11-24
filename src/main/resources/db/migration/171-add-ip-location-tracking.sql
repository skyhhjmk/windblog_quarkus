--liquibase formatted sql

--changeset windblog:171-add-ip-location-tracking
ALTER TABLE comments ADD COLUMN client_ip VARCHAR(64);
ALTER TABLE comments ADD COLUMN ip_location VARCHAR(255);

ALTER TABLE users ADD COLUMN last_login_ip VARCHAR(64);
ALTER TABLE users ADD COLUMN last_login_location VARCHAR(255);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE posts ADD COLUMN publish_ip VARCHAR(64);
ALTER TABLE posts ADD COLUMN publish_location VARCHAR(255);
