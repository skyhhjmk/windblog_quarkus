ALTER TABLE posts ADD COLUMN IF NOT EXISTS search_document tsvector;

CREATE OR REPLACE FUNCTION windblog_update_post_search_document()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    published_content TEXT;
BEGIN
    SELECT COALESCE(string_agg(value, ' '), '')
      INTO published_content
      FROM jsonb_each_text(COALESCE(
          (SELECT content_markdown FROM post_revisions WHERE id = NEW.published_revision_id),
          '{}'::jsonb));

    NEW.search_document = to_tsvector(
        'simple',
        concat_ws(' ', NEW.slug, NEW.title::text, NEW.summary::text, NEW.ai_summary::text,
                  NEW.seo_title, NEW.seo_keywords, NEW.seo_description, published_content));
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_posts_search_document ON posts;
CREATE TRIGGER trg_posts_search_document
    BEFORE INSERT OR UPDATE OF slug, title, summary, ai_summary, seo_title, seo_keywords,
        seo_description, published_revision_id
    ON posts
    FOR EACH ROW
    EXECUTE FUNCTION windblog_update_post_search_document();

UPDATE posts AS post
SET search_document = to_tsvector(
    'simple',
    concat_ws(' ', post.slug, post.title::text, post.summary::text, post.ai_summary::text,
              post.seo_title, post.seo_keywords, post.seo_description,
              COALESCE((SELECT string_agg(value, ' ')
                        FROM jsonb_each_text(COALESCE(revision.content_markdown, '{}'::jsonb))), '')))
FROM post_revisions AS revision
WHERE revision.id = post.published_revision_id;

UPDATE posts
SET search_document = to_tsvector(
    'simple',
    concat_ws(' ', slug, title::text, summary::text, ai_summary::text,
              seo_title, seo_keywords, seo_description))
WHERE search_document IS NULL;

CREATE INDEX IF NOT EXISTS idx_posts_search_document_published
    ON posts USING GIN (search_document)
    WHERE status = 1 AND deleted_at IS NULL AND visibility = 0;
