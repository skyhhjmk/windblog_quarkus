-- Fresh-volume bootstrap only. Existing volumes must be backed up and migrated
-- through the deployment runbook before the image/database is changed.
SELECT 'CREATE DATABASE codex_creator OWNER ' || quote_ident(current_user)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'codex_creator')\gexec
\connect codex_creator
CREATE EXTENSION IF NOT EXISTS vector;
