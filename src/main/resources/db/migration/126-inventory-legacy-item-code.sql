-- liquibase formatted sql
-- changeset windblog:126-inventory-legacy-item-code
UPDATE user_backpack_items
SET item_code = 'store:' || store_item_id
WHERE item_code IS NULL;
ALTER TABLE user_backpack_items ALTER COLUMN item_code SET NOT NULL;
