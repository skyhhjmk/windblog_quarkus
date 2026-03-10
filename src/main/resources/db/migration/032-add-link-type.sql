--liquibase formatted sql


--changeset biliwind:001-add-link-type-column
ALTER TABLE links
    ADD COLUMN type SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN links.type IS '链接类型：0=友情链接，1=网盘资源，2=文章内部链接，3=文章外部链接，4=直链下载链接，5=工具链接，6=文档链接，7=社交媒体链接，8=开源项目链接，99=其他链接';

--rollback ALTER TABLE links DROP COLUMN IF EXISTS type;


--changeset biliwind:002-add-link-type-check-constraint
ALTER TABLE links
    ADD CONSTRAINT chk_links_type
        CHECK (type IN (0, 1, 2, 3, 4, 5, 6, 7, 8, 99));

--rollback ALTER TABLE links DROP CONSTRAINT IF EXISTS chk_links_type;


--changeset biliwind:003-add-link-type-index
CREATE INDEX idx_links_type ON links (type);

--rollback DROP INDEX IF EXISTS idx_links_type;
