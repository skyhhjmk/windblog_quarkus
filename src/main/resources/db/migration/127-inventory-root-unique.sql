-- liquibase formatted sql
-- changeset windblog:127-inventory-root-unique
CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_root_container
    ON inventory_containers(user_id)
    WHERE parent_item_uuid IS NULL;
