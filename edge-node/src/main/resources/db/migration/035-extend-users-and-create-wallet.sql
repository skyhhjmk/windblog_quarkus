-- liquibase formatted sql

-- changeset biliwind:035-extend-users
-- comment 扩展 users 表，添加用户信息和钱包关联字段
ALTER TABLE users
    ADD COLUMN avatar     VARCHAR(512),
    ADD COLUMN nickname   VARCHAR(100),
    ADD COLUMN phone      VARCHAR(20),
    ADD COLUMN extra_info JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN wallet_id  BIGINT;

-- 为 phone 字段添加唯一索引（允许 NULL，但非空时必须唯一）
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users (phone) WHERE phone IS NOT NULL;

-- 为 nickname 添加索引
CREATE INDEX IF NOT EXISTS idx_users_nickname ON users (nickname);

-- 为 wallet_id 添加索引
CREATE INDEX IF NOT EXISTS idx_users_wallet_id ON users (wallet_id);

-- 添加注释
COMMENT ON COLUMN users.avatar IS '用户头像 URL';
COMMENT ON COLUMN users.nickname IS '用户昵称';
COMMENT ON COLUMN users.phone IS '手机号（可选，唯一）';
COMMENT ON COLUMN users.extra_info IS '扩展信息（JSONB，存储个性化设置、社交账号等低频字段）';
COMMENT ON COLUMN users.wallet_id IS '关联的钱包 ID';

-- rollback ALTER TABLE users DROP COLUMN avatar, DROP COLUMN nickname, DROP COLUMN phone, DROP COLUMN extra_info, DROP COLUMN wallet_id;

-- changeset biliwind:035-create-user-wallets
-- comment 创建用户钱包表（极简设计，支持乐观锁）
CREATE TABLE user_wallets
(
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL UNIQUE,
    points_balance BIGINT      NOT NULL DEFAULT 0,
    version        INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 为 user_id 添加索引
CREATE INDEX IF NOT EXISTS idx_user_wallets_user_id ON user_wallets (user_id);

-- 添加注释
COMMENT ON TABLE user_wallets IS '用户钱包表';
COMMENT ON COLUMN user_wallets.id IS '钱包 ID';
COMMENT ON COLUMN user_wallets.user_id IS '用户 ID（外键关联 users.id）';
COMMENT ON COLUMN user_wallets.points_balance IS '积分余额（单位：分）';
COMMENT ON COLUMN user_wallets.version IS '乐观锁版本号';
COMMENT ON COLUMN user_wallets.created_at IS '创建时间';
COMMENT ON COLUMN user_wallets.updated_at IS '更新时间';

-- 添加外键约束
ALTER TABLE user_wallets
    ADD CONSTRAINT fk_user_wallets_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- rollback DROP TABLE IF EXISTS user_wallets;

-- changeset biliwind:035-create-wallet-transactions
-- comment 创建钱包交易流水表
CREATE TABLE wallet_transactions
(
    id            BIGSERIAL PRIMARY KEY,
    wallet_id     BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    change_amount BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    biz_type      VARCHAR(50) NOT NULL,
    biz_id        BIGINT,
    description   VARCHAR(512),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 为 user_id 添加索引（方便按用户查询）
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_user_id ON wallet_transactions (user_id);

-- 为 wallet_id 添加索引
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_wallet_id ON wallet_transactions (wallet_id);

-- 为 biz_type 添加索引
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_biz_type ON wallet_transactions (biz_type);

-- 为 created_at 添加索引（方便按时间排序）
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_created_at ON wallet_transactions (created_at DESC);

-- 添加注释
COMMENT ON TABLE wallet_transactions IS '钱包交易流水表';
COMMENT ON COLUMN wallet_transactions.id IS '交易记录 ID';
COMMENT ON COLUMN wallet_transactions.wallet_id IS '钱包 ID（外键关联 user_wallets.id）';
COMMENT ON COLUMN wallet_transactions.user_id IS '用户 ID（冗余字段，方便查询）';
COMMENT ON COLUMN wallet_transactions.change_amount IS '变动金额（正数为增加，负数为减少）';
COMMENT ON COLUMN wallet_transactions.balance_after IS '变动后余额';
COMMENT ON COLUMN wallet_transactions.biz_type IS '业务类型（REGISTER, REWARD, PURCHASE, ADMIN_ADJUST 等）';
COMMENT ON COLUMN wallet_transactions.biz_id IS '业务 ID（关联具体业务记录）';
COMMENT ON COLUMN wallet_transactions.description IS '描述信息';
COMMENT ON COLUMN wallet_transactions.created_at IS '创建时间';

-- 添加外键约束
ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_wallet
        FOREIGN KEY (wallet_id) REFERENCES user_wallets (id) ON DELETE CASCADE;

-- rollback DROP TABLE IF EXISTS wallet_transactions;
