--liquibase formatted sql


--changeset biliwind:004-create-link-audit-table
CREATE TABLE link_audit (
                            id              BIGSERIAL PRIMARY KEY,
                            link_id         BIGINT NOT NULL REFERENCES links(id) ON DELETE CASCADE,

    -- 1=approved, 2=rejected, 3=spam, 4=pending, 5=error
                            status          SMALLINT NOT NULL,

                            score           NUMERIC(5,2),
                            confidence      NUMERIC(4,3),
                            reason          TEXT,
                            categories      JSONB,

                            auto_approved   BOOLEAN,
                            auto_hidden     BOOLEAN,

                            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

                            CONSTRAINT chk_link_audit_status
                                CHECK (status IN (1,2,3,4,5))
);

COMMENT ON TABLE link_audit IS '友链AI审核记录表，用于记录每次审核结果';

COMMENT ON COLUMN link_audit.id IS '主键ID';
COMMENT ON COLUMN link_audit.link_id IS '关联的链接ID';
COMMENT ON COLUMN link_audit.status IS '审核状态：1=通过，2=拒绝，3=垃圾，4=待审核，5=异常';
COMMENT ON COLUMN link_audit.score IS 'AI审核评分(0-100)';
COMMENT ON COLUMN link_audit.confidence IS 'AI审核置信度(0-1)';
COMMENT ON COLUMN link_audit.reason IS '审核原因说明';
COMMENT ON COLUMN link_audit.categories IS '问题分类(JSON数组)';
COMMENT ON COLUMN link_audit.auto_approved IS '是否AI自动通过';
COMMENT ON COLUMN link_audit.auto_hidden IS '是否AI自动隐藏';
COMMENT ON COLUMN link_audit.created_at IS '审核时间';


--changeset biliwind:005-link-audit-indexes
CREATE INDEX idx_audit_link_time
    ON link_audit (link_id, created_at DESC);

--rollback DROP INDEX IF EXISTS idx_audit_link_time;
--rollback DROP TABLE IF EXISTS link_audit;
