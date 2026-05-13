-- liquibase formatted sql

-- changeset biliwind:050-add-comment-audit-fields
alter table comments
    add column audit_status smallint not null default 0;
alter table comments
    add column audit_type smallint not null default 0;
alter table comments
    add column audit_reason text;

comment on column comments.audit_status is '审核状态：0=未审核 1=审核中 2=审核通过 3=审核拒绝';
comment on column comments.audit_type is '审核类型：0=无 1=AI 审核 2=人工审核';
comment on column comments.audit_reason is '审核原因/理由';
