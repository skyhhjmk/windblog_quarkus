-- Add post statistics fields for Elasticsearch indexing
-- Add view_count, featured, allow_comment fields to posts table

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS view_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS allow_comment BOOLEAN NOT NULL DEFAULT TRUE;

-- Add index for view_count to support sorting by popularity
CREATE INDEX IF NOT EXISTS idx_posts_view_count ON posts (view_count DESC);

-- Add index for featured posts
CREATE INDEX IF NOT EXISTS idx_posts_featured ON posts (featured) WHERE featured = TRUE;
