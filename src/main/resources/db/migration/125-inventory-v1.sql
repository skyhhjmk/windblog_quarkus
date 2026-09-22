-- liquibase formatted sql
-- changeset windblog:125-inventory-v1
CREATE EXTENSION IF NOT EXISTS pgcrypto;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS item_code VARCHAR(128);
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS width INT NOT NULL DEFAULT 1;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS height INT NOT NULL DEFAULT 1;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS stackable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS max_stack_size INT NOT NULL DEFAULT 1;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS container_rows INT;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS container_columns INT;
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS definition_version VARCHAR(64) NOT NULL DEFAULT '1';
ALTER TABLE store_items ADD COLUMN IF NOT EXISTS deprecated BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE store_items SET item_code = 'store:' || id WHERE item_code IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_store_items_item_code ON store_items(item_code);

ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS instance_uuid UUID;
UPDATE user_backpack_items SET instance_uuid = gen_random_uuid() WHERE instance_uuid IS NULL;
ALTER TABLE user_backpack_items ALTER COLUMN instance_uuid SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_backpack_instance_uuid ON user_backpack_items(instance_uuid);
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS item_code VARCHAR(128);
UPDATE user_backpack_items b SET item_code = s.item_code FROM store_items s WHERE b.store_item_id = s.id AND b.item_code IS NULL;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS definition_version VARCHAR(64) NOT NULL DEFAULT '1';
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS quantity INT NOT NULL DEFAULT 1;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS pos_x INT NOT NULL DEFAULT 0;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS pos_y INT NOT NULL DEFAULT 0;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS rotated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS width INT NOT NULL DEFAULT 1;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS height INT NOT NULL DEFAULT 1;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS max_stack_size INT NOT NULL DEFAULT 1;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS container_id BIGINT;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS parent_instance_uuid UUID;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS deprecated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS definition_snapshot JSONB;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS extra_data JSONB;
ALTER TABLE user_backpack_items ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE user_backpack_items b
SET width = s.width, height = s.height, max_stack_size = s.max_stack_size,
    definition_version = s.definition_version
FROM store_items s WHERE b.store_item_id = s.id;

-- Legacy rows had no coordinates. Give them deterministic non-overlapping root positions.
WITH ranked AS (
    SELECT id,
           ((row_number() OVER (PARTITION BY user_id ORDER BY acquired_at, id) - 1) % 10)::INT AS new_x,
           (((row_number() OVER (PARTITION BY user_id ORDER BY acquired_at, id) - 1) / 10)::INT) AS new_y
    FROM user_backpack_items
)
UPDATE user_backpack_items b
SET pos_x = ranked.new_x, pos_y = ranked.new_y
FROM ranked WHERE ranked.id = b.id;

CREATE TABLE IF NOT EXISTS inventory_warehouses
(
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0,
    snapshot JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_containers
(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_item_uuid UUID UNIQUE,
    rows INT NOT NULL,
    columns INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    snapshot JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_inventory_container_size CHECK (rows > 0 AND columns > 0)
);
CREATE INDEX IF NOT EXISTS idx_inventory_containers_user_id ON inventory_containers(user_id);

ALTER TABLE inventory_containers
    DROP CONSTRAINT IF EXISTS fk_inventory_container_parent;
ALTER TABLE inventory_containers
    ADD CONSTRAINT fk_inventory_container_parent FOREIGN KEY (parent_item_uuid)
        REFERENCES user_backpack_items(instance_uuid) ON DELETE CASCADE;

ALTER TABLE user_backpack_items
    DROP CONSTRAINT IF EXISTS fk_inventory_backpack_container;
ALTER TABLE user_backpack_items
    ADD CONSTRAINT fk_inventory_backpack_container FOREIGN KEY (container_id)
        REFERENCES inventory_containers(id) ON DELETE SET NULL;
ALTER TABLE user_backpack_items
    DROP CONSTRAINT IF EXISTS fk_inventory_backpack_parent;
ALTER TABLE user_backpack_items
    ADD CONSTRAINT fk_inventory_backpack_parent FOREIGN KEY (parent_instance_uuid)
        REFERENCES user_backpack_items(instance_uuid) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS inventory_operations
(
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(200) NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    request_revision BIGINT,
    result_instance_uuid UUID,
    result JSONB,
    trace_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inventory_operation_key UNIQUE(user_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_inventory_operations_user_created
    ON inventory_operations(user_id, created_at DESC);

ALTER TABLE user_backpack_items
    DROP CONSTRAINT IF EXISTS ck_inventory_quantity;
ALTER TABLE user_backpack_items
    ADD CONSTRAINT ck_inventory_quantity CHECK (quantity >= 1);
ALTER TABLE user_backpack_items
    DROP CONSTRAINT IF EXISTS ck_inventory_dimensions;
ALTER TABLE user_backpack_items
    ADD CONSTRAINT ck_inventory_dimensions CHECK (width > 0 AND height > 0 AND max_stack_size > 0);
