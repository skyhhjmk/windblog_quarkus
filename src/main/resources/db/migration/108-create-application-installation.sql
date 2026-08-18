-- liquibase formatted sql
-- changeset biliwind:108-create-application-installation
CREATE TABLE application_installation
(
    id           SMALLINT PRIMARY KEY,
    installed    BOOLEAN NOT NULL DEFAULT FALSE,
    installed_at TIMESTAMPTZ,
    installed_by BIGINT
);

-- Existing deployments that already have a usable SUPER_ADMIN are installed.
INSERT INTO application_installation (id, installed, installed_by)
SELECT 1,
       EXISTS (SELECT 1 FROM users
               WHERE role_name = 'SUPER_ADMIN' AND status = 1 AND deleted_at IS NULL),
       (SELECT id FROM users
        WHERE role_name = 'SUPER_ADMIN' AND status = 1 AND deleted_at IS NULL
        ORDER BY id LIMIT 1);

-- rollback DROP TABLE application_installation;
