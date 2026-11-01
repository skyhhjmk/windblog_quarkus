-- liquibase formatted sql

-- changeset biliwind:152-add-post-seo-i18n splitStatements:false
alter table posts add column seo_title_i18n jsonb;
alter table posts add column seo_keywords_i18n jsonb;
alter table posts add column seo_description_i18n jsonb;

-- Legacy single values are attached to the article's primary language: the
-- first title key, preferring zh-cn then en-US.
update posts
set seo_title_i18n = case when seo_title is null then null else jsonb_build_object(
        coalesce((select k from jsonb_object_keys(title) k
                  order by case lower(k) when 'zh-cn' then 0 when 'en-us' then 1 else 2 end, k
                  limit 1), 'zh-cn'), seo_title) end,
    seo_keywords_i18n = case when seo_keywords is null then null else jsonb_build_object(
        coalesce((select k from jsonb_object_keys(title) k
                  order by case lower(k) when 'zh-cn' then 0 when 'en-us' then 1 else 2 end, k
                  limit 1), 'zh-cn'), seo_keywords) end,
    seo_description_i18n = case when seo_description is null then null else jsonb_build_object(
        coalesce((select k from jsonb_object_keys(title) k
                  order by case lower(k) when 'zh-cn' then 0 when 'en-us' then 1 else 2 end, k
                  limit 1), 'zh-cn'), seo_description) end;

drop trigger if exists trg_posts_search_document on posts;
alter table posts drop column seo_title;
alter table posts drop column seo_keywords;
alter table posts drop column seo_description;
alter table posts rename column seo_title_i18n to seo_title;
alter table posts rename column seo_keywords_i18n to seo_keywords;
alter table posts rename column seo_description_i18n to seo_description;

comment on column posts.seo_title is 'SEO 标题（多语言 JSONB）';
comment on column posts.seo_keywords is 'SEO 关键词（多语言 JSONB）';
comment on column posts.seo_description is 'SEO 描述（多语言 JSONB）';

-- Keep the PostgreSQL fallback search in sync with the new JSONB shape.
create or replace function windblog_update_post_search_document() returns trigger as $$
declare published_content text;
begin
    select coalesce(string_agg(value, ' '), '') into published_content
    from jsonb_each_text(coalesce(
        (select content_markdown from post_revisions where id = new.published_revision_id),
        '{}'::jsonb));
    new.search_document = to_tsvector(
        'simple',
        concat_ws(' ', new.slug, new.title::text, new.summary::text, new.ai_summary::text,
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(new.seo_title)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(new.seo_keywords)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(new.seo_description)), ''),
                  published_content));
    return new;
end;
$$ language plpgsql;

drop trigger if exists trg_posts_search_document on posts;
create trigger trg_posts_search_document
    before insert or update of slug, title, summary, ai_summary, seo_title, seo_keywords,
        seo_description, published_revision_id
    on posts
    for each row
    execute function windblog_update_post_search_document();

update posts as post
set search_document = to_tsvector(
        'simple',
        concat_ws(' ', post.slug, post.title::text, post.summary::text, post.ai_summary::text,
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(post.seo_title)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(post.seo_keywords)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(post.seo_description)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(revision.content_markdown)), '')))
from post_revisions as revision
where revision.id = post.published_revision_id;

update posts
set search_document = to_tsvector(
        'simple',
        concat_ws(' ', slug, title::text, summary::text, ai_summary::text,
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(seo_title)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(seo_keywords)), ''),
                  coalesce((select string_agg(value, ' ') from jsonb_each_text(seo_description)), '')))
where search_document is null;

-- rollback Altering back would lose translations; restore the zh-cn value by hand if required.
