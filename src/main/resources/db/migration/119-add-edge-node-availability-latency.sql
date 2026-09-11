-- liquibase formatted sql

-- changeset biliwind:119-add-edge-node-availability-latency
alter table edge_node_availability_samples
    add column if not exists latency_ms bigint;

comment on column edge_node_availability_samples.latency_ms is
    '采样时最近心跳或会话的新鲜度延迟（毫秒），不是网络 RTT';

-- rollback alter table edge_node_availability_samples drop column if exists latency_ms;
