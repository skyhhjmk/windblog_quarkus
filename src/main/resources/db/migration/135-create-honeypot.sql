-- changeset biliwind:135-create-honeypot
CREATE TABLE honeypot_rules (
    rule_key varchar(64) PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT true,
    action varchar(16) NOT NULL DEFAULT 'OBSERVE',
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_honeypot_rule_action CHECK (action IN ('OBSERVE', 'BLOCK'))
);

INSERT INTO honeypot_rules (rule_key, enabled, action) VALUES
    ('sql_injection', true, 'OBSERVE'),
    ('xss', true, 'OBSERVE'),
    ('path_traversal', true, 'OBSERVE'),
    ('command_injection', true, 'OBSERVE'),
    ('template_injection', true, 'OBSERVE'),
    ('scanner_probe', true, 'OBSERVE'),
    ('honeypot_paths', true, 'OBSERVE');

CREATE TABLE honeypot_events (
    id bigserial PRIMARY KEY,
    client_ip varchar(128) NOT NULL,
    remote_ip varchar(128),
    method varchar(16) NOT NULL,
    request_uri text NOT NULL,
    user_agent text,
    matched_rules jsonb NOT NULL,
    action varchar(16) NOT NULL,
    body_truncated boolean NOT NULL DEFAULT false,
    body_bytes integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_honeypot_event_action CHECK (action IN ('OBSERVE', 'BLOCK'))
);

CREATE TABLE honeypot_event_samples (
    event_id bigint PRIMARY KEY REFERENCES honeypot_events(id) ON DELETE CASCADE,
    request_headers jsonb NOT NULL,
    body_sample bytea
);

CREATE INDEX idx_honeypot_events_created_at ON honeypot_events (created_at DESC, id DESC);
CREATE INDEX idx_honeypot_events_client_ip ON honeypot_events (client_ip, created_at DESC);
CREATE INDEX idx_honeypot_events_matched_rules ON honeypot_events USING gin (matched_rules);

-- rollback DROP TABLE honeypot_event_samples;
-- rollback DROP TABLE honeypot_events;
-- rollback DROP TABLE honeypot_rules;
