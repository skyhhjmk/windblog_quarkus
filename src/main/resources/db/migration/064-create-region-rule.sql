-- ============================================
-- 区域匹配规则表
-- ============================================
CREATE TABLE region_rules
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    rule_type  VARCHAR(20)  NOT NULL, -- domain, language
    pattern    VARCHAR(255) NOT NULL, -- 匹配模式: blog.cn, zh-CN
    region     VARCHAR(20)  NOT NULL, -- 映射到的区域
    priority   INT          NOT NULL DEFAULT 0,
    is_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_region_rules_enabled ON region_rules (is_enabled);
CREATE INDEX idx_region_rules_priority ON region_rules (priority DESC);

COMMENT ON TABLE region_rules IS '区域匹配规则表';
COMMENT ON COLUMN region_rules.rule_type IS '规则类型: domain (域名), language (Accept-Language)';
COMMENT ON COLUMN region_rules.pattern IS '匹配字符串，如 blog.cn 或 zh-CN';
COMMENT ON COLUMN region_rules.region IS '目标区域标识符';
