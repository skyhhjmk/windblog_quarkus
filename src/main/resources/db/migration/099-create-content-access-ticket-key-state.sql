CREATE TABLE IF NOT EXISTS content_access_ticket_key_state (
    id INTEGER PRIMARY KEY,
    current_version INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO content_access_ticket_key_state (id, current_version)
VALUES (1, 1)
ON CONFLICT (id) DO NOTHING;
