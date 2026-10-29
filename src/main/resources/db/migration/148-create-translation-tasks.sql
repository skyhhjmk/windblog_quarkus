-- liquibase formatted sql

-- changeset biliwind:148-create-translation-tasks
create table translation_tasks
(
    id                bigserial primary key,
    post_id           bigint       not null,
    target_language   varchar(32)  not null,
    source_language   varchar(32),
    overwrite         boolean      not null default false,
    status            varchar(24)  not null default 'QUEUED',
    message           text,
    revision_number   integer,
    published         boolean      not null default false,
    attempts          integer      not null default 0,
    max_attempts      integer      not null default 3,
    next_attempt_at   timestamptz,
    batch_id          varchar(64),
    created_by        bigint,
    created_at        timestamptz  not null default now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    updated_at        timestamptz  not null default now()
);

create index idx_translation_tasks_queue on translation_tasks (status, next_attempt_at, id);
create index idx_translation_tasks_batch on translation_tasks (batch_id, id);
create index idx_translation_tasks_post on translation_tasks (post_id, id desc);

comment on table translation_tasks is '异步翻译任务队列';
comment on column translation_tasks.status is 'QUEUED/RUNNING/SUCCEEDED/SKIPPED/FAILED/CANCELED';

-- rollback drop table translation_tasks;
