-- liquibase formatted sql

-- changeset biliwind:1773053102587-10
ALTER TABLE link_monitor_log
    ADD created_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-11
ALTER TABLE link_monitor_log
    ALTER COLUMN created_at SET NOT NULL;


-- changeset biliwind:1773053102587-12
ALTER TABLE ai_provider_configs
    ADD created_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-13
ALTER TABLE ai_provider_configs
    ALTER COLUMN created_at SET NOT NULL;

-- changeset biliwind:1773053102587-14
ALTER TABLE post_media
    ADD updated_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-15
ALTER TABLE post_media
    ALTER COLUMN updated_at SET NOT NULL;

-- changeset biliwind:1773053102587-16
ALTER TABLE categories
    ADD updated_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-17
ALTER TABLE categories
    ALTER COLUMN updated_at SET NOT NULL;

-- changeset biliwind:1773053102587-18
ALTER TABLE comments
    ADD updated_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-19
ALTER TABLE comments
    ALTER COLUMN updated_at SET NOT NULL;

-- changeset biliwind:1773053102587-18
ALTER TABLE media
    ADD updated_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-19
ALTER TABLE media
    ALTER COLUMN updated_at SET NOT NULL;

-- changeset biliwind:1773053102587-18
ALTER TABLE tags
    ADD created_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:1773053102587-19
ALTER TABLE tags
    ALTER COLUMN created_at SET NOT NULL;

