-- 创建用户签到表
CREATE TABLE user_check_ins
(
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    check_in_date DATE        NOT NULL,
    reward_info   JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 添加唯一约束，确保用户每天只能签到一次
CREATE UNIQUE INDEX idx_user_check_ins_user_date ON user_check_ins (user_id, check_in_date);

-- 添加外键关联到用户表
ALTER TABLE user_check_ins
    ADD CONSTRAINT fk_user_check_ins_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- 为查询提供索引
CREATE INDEX idx_user_check_ins_user_id ON user_check_ins (user_id);
CREATE INDEX idx_user_check_ins_date ON user_check_ins (check_in_date);

-- 初始化全局签到配置
INSERT INTO system_settings (config_key, config_value, config_type, group_name, ui_schema, description)
VALUES ('daily_check_in_rewards',
        '{
          "points": 10,
          "items": [],
          "experience": 5
        }'::jsonb,
        'json',
        'wallet',
        '{
          "type": "object",
          "fields": [
            {
              "key": "points",
              "label": "积分奖励",
              "widget": "input",
              "required": true
            },
            {
              "key": "experience",
              "label": "经验奖励",
              "widget": "input"
            }
          ]
        }'::jsonb,
        '每日签到基础奖励配置');
