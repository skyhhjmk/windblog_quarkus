-- liquibase formatted sql
-- changeset biliwind:052-add-ai-audit-settings
INSERT INTO system_settings (config_key, config_value, group_name, ui_schema, description)
VALUES ('ai_comment_audit',
        '{
          "prompt": "你是一个评论审核专家。请审核以下评论内容，判断其是否包含不当内容（如：垃圾广告、人身攻击、违法违规、色情等）。\n请严格按照以下 JSON 格式回答，不要有任何多余文字：\n{\"isSafe\": true/false, \"reason\": \"理由\"}\n\n待审核评论：\n{{content}}",
          "auto_audit": true
        }',
        'ai',
        '{
          "type": "object",
          "fields": [
            {
              "key": "prompt",
              "label": "AI 审核提示词",
              "widget": "textarea",
              "required": true,
              "description": "使用 {{content}} 作为评论内容的占位符"
            },
            {
              "key": "auto_audit",
              "label": "启用自动 AI 审核",
              "widget": "switch",
              "description": "提交评论后自动进入 AI 审核队列"
            }
          ]
        }',
        'AI 评论审核配置');
