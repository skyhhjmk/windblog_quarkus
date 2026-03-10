-- liquibase formatted sql

-- changeset biliwind:023-create-ai-provider-config
CREATE TABLE ai_provider_configs
(
    id         BIGSERIAL PRIMARY KEY,
    provider   VARCHAR(64) NOT NULL UNIQUE,
    enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    endpoint   VARCHAR(512),
    api_key    VARCHAR(512),
    model      VARCHAR(128),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO ai_provider_configs (provider, enabled, endpoint, model)
VALUES ('OLLAMA', FALSE, 'http://localhost:11434', 'ollama/llama3'),
       ('CHATGLM', FALSE, 'http://localhost:9000', 'chatglm2');
