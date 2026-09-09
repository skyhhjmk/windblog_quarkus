--liquibase formatted sql

--changeset biliwind:118-seed-edge-connection-permission

alter table if exists wesp_sync_cursors
    add column if not exists full_sync_requested boolean not null default false;

-- The target administrator has just supplied a password for this one-time
-- bootstrap. Keep the action explicit while allowing a normal ADMIN account;
-- the current primary's /connect endpoint remains high-risk and step-up gated.
insert into admin_role_permissions (role_name, permission, enabled)
values ('ADMIN', 'edge.connection.bootstrap', true)
on conflict (role_name, permission) do nothing;

--rollback delete from admin_role_permissions where role_name = 'ADMIN' and permission = 'edge.connection.bootstrap';
