-- liquibase formatted sql

-- changeset biliwind:010-create-audit-logs
create table audit_logs
(
    id            bigserial primary key,
    entity_type   varchar(50)  not null,
    entity_id     bigint       not null,
    action        varchar(50)  not null,
    old_value     jsonb,
    new_value     jsonb,
    performed_by  bigint,
    created_at    timestamptz  not null default now()
);

comment on table audit_logs is '操作审计日志表';

comment on column audit_logs.id is '日志ID';
comment on column audit_logs.entity_type is '实体类型，例如 post/media';
comment on column audit_logs.entity_id is '实体ID';
comment on column audit_logs.action is '操作类型，例如 create/update/delete';
comment on column audit_logs.old_value is '旧值 JSONB';
comment on column audit_logs.new_value is '新值 JSONB';
comment on column audit_logs.performed_by is '操作人ID';
comment on column audit_logs.created_at is '操作时间';

-- rollback drop table audit_logs;
