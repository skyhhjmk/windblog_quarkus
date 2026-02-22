-- liquibase formatted sql

-- changeset biliwind:024-add-media-uploader
alter table media
    add column file_name varchar(255);

alter table media
    add column uploaded_by bigint;

alter table media
    add constraint fk_media_uploaded_by foreign key (uploaded_by)
        references users (id)
        on delete set null;

comment on column media.file_name is '上传文件名';
comment on column media.uploaded_by is '上传用户';

-- rollback
alter table media
    drop constraint if exists fk_media_uploaded_by;
alter table media
    drop column if exists uploaded_by;
alter table media
    drop column if exists file_name;
