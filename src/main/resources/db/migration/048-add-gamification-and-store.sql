-- liquibase formatted sql

-- changeset windblog:048-add-gamification-and-store

-- 创建商店物品表
CREATE TABLE store_items
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128)             NOT NULL,
    description TEXT,
    price       BIGINT                   NOT NULL,
    rarity      VARCHAR(32),
    type        VARCHAR(32),
    extra_info  JSONB,
    status      SMALLINT                 NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 创建用户背包物品表
CREATE TABLE user_backpack_items
(
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT                   NOT NULL,
    store_item_id BIGINT                   NOT NULL,
    acquired_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_backpack_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_backpack_items_store_item FOREIGN KEY (store_item_id) REFERENCES store_items (id) ON DELETE CASCADE
);
CREATE INDEX idx_user_backpack_items_user_id ON user_backpack_items (user_id);

-- 创建购买记录表
CREATE TABLE user_purchase_records
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT                   NOT NULL,
    target_type VARCHAR(32)              NOT NULL,
    target_id   BIGINT                   NOT NULL,
    points_paid BIGINT                   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_purchase_records_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_user_purchase_records_user_id ON user_purchase_records (user_id);
CREATE INDEX idx_user_purchase_records_target ON user_purchase_records (target_type, target_id);

-- 更新用户表
ALTER TABLE users
    ADD COLUMN level INT NOT NULL DEFAULT 1;
ALTER TABLE users
    ADD COLUMN exp INT NOT NULL DEFAULT 0;
ALTER TABLE users
    ADD COLUMN backpack_capacity INT NOT NULL DEFAULT 36;

-- 更新文章表
ALTER TABLE posts
    ADD COLUMN extra_info JSONB;
