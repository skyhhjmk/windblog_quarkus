--liquibase formatted sql
--changeset biliwind:116-create-wesp-sync

create sequence if not exists wesp_operation_seq start with 1 increment by 1;

create table if not exists wesp_sync_operations
(
    row_id       bigserial primary key,
    op_id        varchar(200) not null unique,
    node_id      varchar(100) not null,
    dataset_id   varchar(100) not null,
    incarnation  varchar(100) not null,
    seq          bigint not null,
    entity_type  varchar(64) not null,
    action       varchar(32) not null,
    entity_id    varchar(128) not null,
    payload      text not null,
    payload_hash varchar(64) not null,
    status       varchar(32) not null default 'PENDING',
    sent_at      timestamp with time zone,
    applied_at   timestamp with time zone,
    last_error   varchar(512),
    created_at   timestamp with time zone not null default current_timestamp,
    updated_at   timestamp with time zone not null default current_timestamp
);

create index if not exists idx_wesp_operations_pending
    on wesp_sync_operations (status, sent_at, row_id);
create index if not exists idx_wesp_operations_changes
    on wesp_sync_operations (row_id);
create index if not exists idx_wesp_operations_node_seq
    on wesp_sync_operations (node_id, incarnation, seq);

create table if not exists wesp_sync_batches
(
    batch_id    varchar(100) primary key,
    body_hash   varchar(64) not null,
    created_at  timestamp with time zone not null default current_timestamp
);

create table if not exists wesp_sync_cursors
(
    peer_id     varchar(100) primary key,
    cursor_row  bigint not null default 0,
    updated_at  timestamp with time zone not null default current_timestamp
);

create table if not exists wesp_sync_blocks
(
    hash        varchar(64) primary key,
    size_bytes  bigint not null,
    file_path   text not null,
    created_at  timestamp with time zone not null default current_timestamp,
    last_seen_at timestamp with time zone not null default current_timestamp
);

create table if not exists wesp_sync_receipts
(
    receipt_id  varchar(100) primary key,
    node_id     varchar(100) not null,
    stage       varchar(32) not null,
    payload     text not null,
    created_at  timestamp with time zone not null default current_timestamp,
    updated_at  timestamp with time zone not null default current_timestamp
);

create table if not exists wesp_sync_manifests
(
    manifest_id varchar(64) primary key,
    media_id    varchar(128) not null,
    variant     varchar(32) not null,
    file_hash   varchar(64) not null,
    file_size   bigint not null,
    payload     text not null,
    created_at  timestamp with time zone not null default current_timestamp,
    updated_at  timestamp with time zone not null default current_timestamp
);
create unique index if not exists uq_wesp_manifest_media_variant
    on wesp_sync_manifests (media_id, variant);

comment on table wesp_sync_operations is 'WESP v1 durable operation outbox and inbox';
comment on table wesp_sync_batches is 'WESP v1 idempotent batch receipts';
comment on table wesp_sync_blocks is 'WESP v1 content-addressed attachment blocks';
comment on table wesp_sync_receipts is 'WESP v1 durable delivery receipts';
comment on table wesp_sync_manifests is 'WESP v1 content-addressed attachment manifests';

--rollback drop table if exists wesp_sync_blocks;
--rollback drop table if exists wesp_sync_receipts;
--rollback drop table if exists wesp_sync_manifests;
--rollback drop table if exists wesp_sync_cursors;
--rollback drop table if exists wesp_sync_operations;
--rollback drop table if exists wesp_sync_batches;
--rollback drop sequence if exists wesp_operation_seq;
