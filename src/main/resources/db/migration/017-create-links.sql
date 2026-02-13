--liquibase formatted sql


--changeset biliwind:001-create-links-table
CREATE TABLE links (
                       id              BIGSERIAL PRIMARY KEY,
                       name            TEXT NOT NULL,
                       url             TEXT NOT NULL UNIQUE,
                       description     TEXT,
                       image           TEXT,
                       icon            TEXT,
                       sort_order      INT NOT NULL DEFAULT 0,

    -- 1=visible, 2=hidden, 3=archived
                       status          SMALLINT NOT NULL DEFAULT 1,

                       target          TEXT NOT NULL DEFAULT '_blank',

    -- 1=direct, 2=goto, 3=iframe, 4=info
                       redirect_type   SMALLINT NOT NULL DEFAULT 1,

                       show_url        BOOLEAN NOT NULL DEFAULT false,
                       content         TEXT,
                       email           TEXT,
                       callback_url    TEXT,
                       note            TEXT,
                       seo_title       TEXT,
                       seo_keywords    TEXT,
                       seo_description TEXT,
                       settings        JSONB,
                       created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

                       CONSTRAINT chk_links_status
                           CHECK (status IN (1,2,3)),

                       CONSTRAINT chk_links_redirect_type
                           CHECK (redirect_type IN (1,2,3,4))
);

COMMENT ON TABLE links IS '友情链接主表，用于存储网站链接及相关配置信息';

COMMENT ON COLUMN links.id IS '主键ID';
COMMENT ON COLUMN links.name IS '链接名称';
COMMENT ON COLUMN links.url IS '链接地址（唯一）';
COMMENT ON COLUMN links.description IS '链接简要描述';
COMMENT ON COLUMN links.image IS '链接配图URL';
COMMENT ON COLUMN links.icon IS '网站图标URL';
COMMENT ON COLUMN links.sort_order IS '排序权重，数字越小越靠前';

COMMENT ON COLUMN links.status IS '状态：1=显示，2=隐藏，3=归档';

COMMENT ON COLUMN links.target IS '打开方式，如 _blank、_self 等';

COMMENT ON COLUMN links.redirect_type IS '跳转方式：1=直接跳转，2=中转页跳转，3=iframe内嵌，4=详情页';

COMMENT ON COLUMN links.show_url IS '是否在中转页显示原始URL';

COMMENT ON COLUMN links.content IS '链接详细介绍内容（Markdown格式）';
COMMENT ON COLUMN links.email IS '所有者电子邮件';
COMMENT ON COLUMN links.callback_url IS '回调地址';
COMMENT ON COLUMN links.note IS '管理员备注';

COMMENT ON COLUMN links.seo_title IS 'SEO标题';
COMMENT ON COLUMN links.seo_keywords IS 'SEO关键词';
COMMENT ON COLUMN links.seo_description IS 'SEO描述';

COMMENT ON COLUMN links.settings IS '自定义配置(JSONB)，包含监控、自动审核、标签等扩展字段';

COMMENT ON COLUMN links.created_at IS '创建时间';
COMMENT ON COLUMN links.updated_at IS '更新时间';


--changeset biliwind:002-enable-pgtrgm-before-index
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--rollback -- nothing


--changeset biliwind:003-links-indexes
CREATE INDEX idx_links_sort_visible
    ON links (sort_order)
    WHERE status = 1;

CREATE INDEX idx_links_name_trgm
    ON links
        USING GIN (name gin_trgm_ops);

CREATE INDEX idx_links_settings_tags
    ON links
        USING GIN ((settings->'tags'));

--rollback DROP INDEX IF EXISTS idx_links_settings_tags;
--rollback DROP INDEX IF EXISTS idx_links_name_trgm;
--rollback DROP INDEX IF EXISTS idx_links_sort_visible;

--rollback DROP TABLE IF EXISTS links;
