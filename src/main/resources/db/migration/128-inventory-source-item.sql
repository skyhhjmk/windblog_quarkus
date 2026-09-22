-- liquibase formatted sql
-- changeset windblog:128-inventory-source-item
-- Compile-time item definitions do not require a StoreItem row.
ALTER TABLE user_backpack_items ALTER COLUMN store_item_id DROP NOT NULL;
