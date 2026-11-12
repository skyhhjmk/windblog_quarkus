--liquibase formatted sql
--changeset biliwind:167-create-wesp-sequence-frontiers

create table if not exists wesp_sync_sequence_frontiers
(
    node_id     varchar(100) not null,
    incarnation varchar(100) not null,
    dataset_id  varchar(100) not null,
    applied_seq bigint       not null default 0,
    updated_at  timestamp with time zone not null default current_timestamp,
    primary key (node_id, incarnation, dataset_id)
);

comment on table wesp_sync_sequence_frontiers is 'WESP v1 highest contiguous applied sequence per sender incarnation';

--rollback drop table if exists wesp_sync_sequence_frontiers;
