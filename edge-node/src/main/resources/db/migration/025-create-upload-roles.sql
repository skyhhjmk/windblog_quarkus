-- liquibase formatted sql

-- changeset biliwind:025-create-upload-roles
create table upload_roles
(
    name                    varchar(64) primary key,
    display_name            varchar(128),
    description             text,
    can_upload              boolean     not null default false,
    allowed_mime_types      jsonb,
    max_single_upload_bytes bigint,
    max_total_upload_bytes  bigint,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);

comment on table upload_roles is '管理上传权限的角色';
comment on column upload_roles.name is '角色标识';
comment on column upload_roles.display_name is '角色名称';
comment on column upload_roles.description is '角色描述';
comment on column upload_roles.can_upload is '是否允许上传';
comment on column upload_roles.allowed_mime_types is '允许的 MIME 列表';
comment on column upload_roles.max_single_upload_bytes is '单个文件最大字节数，null=无限制';
comment on column upload_roles.max_total_upload_bytes is '总上传配额字节数，null=不限制';

alter table users
    add column role_name varchar(64);

alter table users
    add constraint fk_users_role_name foreign key (role_name)
        references upload_roles (name)
        on delete set null;

comment on column users.role_name is '用户所属角色';

-- rollback drop table upload_roles;
-- rollback alter table users drop constraint fk_users_role_name;
-- rollback alter table users drop column role_name;
