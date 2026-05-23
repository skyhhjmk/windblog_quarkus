-- liquibase formatted sql

-- changeset biliwind:071-create-edge-node-availability-samples
create table edge_node_availability_samples
(
    id         bigserial primary key,
    node_id    varchar(100)             not null,
    sampled_at timestamp with time zone not null,
    is_online  boolean                  not null
);

create index idx_edge_node_availability_node_time
    on edge_node_availability_samples (node_id, sampled_at);

comment on table edge_node_availability_samples is '边缘节点在线率采样表';
comment on column edge_node_availability_samples.node_id is '边缘节点ID';
comment on column edge_node_availability_samples.sampled_at is '采样时间';
comment on column edge_node_availability_samples.is_online is '采样时持久数据通道是否在线';

-- rollback drop table if exists edge_node_availability_samples;
