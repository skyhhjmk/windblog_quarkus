-- liquibase formatted sql

-- changeset biliwind:075-add-edge-node-fixed-deployment-ports
alter table edge_nodes
    add column if not exists edge_db_port integer,
    add column if not exists edge_redis_port integer,
    add column if not exists edge_http_port integer;

update edge_nodes
set edge_db_port = (20000 + floor(random() * 40000))::integer
where edge_db_port is null;

update edge_nodes
set edge_redis_port = (20000 + floor(random() * 40000))::integer
where edge_redis_port is null;

update edge_nodes
set edge_http_port = (20000 + floor(random() * 40000))::integer
where edge_http_port is null;

comment on column edge_nodes.edge_db_port is '边缘节点本地 PostgreSQL 对外映射端口';
comment on column edge_nodes.edge_redis_port is '边缘节点本地 Redis 对外映射端口';
comment on column edge_nodes.edge_http_port is '边缘节点 HTTP 对外映射端口';

-- rollback alter table edge_nodes drop column if exists edge_http_port;
-- rollback alter table edge_nodes drop column if exists edge_redis_port;
-- rollback alter table edge_nodes drop column if exists edge_db_port;
