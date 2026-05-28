-- changeset biliwind:078-create-edge-sync-records

create table edge_sync_records
(
    id            bigserial primary key,
    node_id       varchar(100)             not null,
    entity_type   varchar(64)              not null,
    entity_id     varchar(64)              not null,
    action        varchar(32)              not null,
    status        varchar(32)              not null,
    retry_count   integer                  not null default 0,
    error_message varchar(1000),
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now()
);

create index idx_edge_sync_records_node_status
    on edge_sync_records (node_id, status, updated_at desc);

create index idx_edge_sync_records_entity
    on edge_sync_records (entity_type, entity_id, updated_at desc);

comment on table edge_sync_records is '边缘节点同步投递记录，用于排查漏同步、失败重试和节点收敛问题';
comment on column edge_sync_records.status is 'PENDING、SUCCESS、FAILED';

-- rollback drop table if exists edge_sync_records;
