-- changeset biliwind:080-hardening-storage-edge-risk-surfaces

create index if not exists idx_media_storage_classes_gin
    on media using gin (storage_classes);

with ranked_primary_storage_class as (
    select id,
           row_number() over (order by priority asc, id asc) as primary_rank
    from storage_class
    where is_primary = true
)
update storage_class
set is_primary = false,
    role = 'backup'
where id in (
    select id
    from ranked_primary_storage_class
    where primary_rank > 1
);

create unique index if not exists idx_storage_class_single_primary
    on storage_class ((true))
    where is_primary = true;

alter table media
    add constraint chk_media_visibility_regions_json_array
        check (visibility_regions is null or jsonb_typeof(visibility_regions) = 'array');

alter table posts
    add constraint chk_posts_visibility_regions_json_array
        check (visibility_regions is null or jsonb_typeof(visibility_regions) = 'array');

alter table storage_class
    add constraint chk_storage_class_content_regions_json_array
        check (content_regions is null or jsonb_typeof(content_regions) = 'array');

alter table media
    add constraint chk_media_sync_storage_classes_json_array
        check (sync_storage_classes is null or jsonb_typeof(sync_storage_classes) = 'array');

alter table media
    add constraint chk_media_skip_storage_classes_json_array
        check (skip_storage_classes is null or jsonb_typeof(skip_storage_classes) = 'array');

-- rollback alter table media drop constraint if exists chk_media_skip_storage_classes_json_array;
-- rollback alter table media drop constraint if exists chk_media_sync_storage_classes_json_array;
-- rollback alter table storage_class drop constraint if exists chk_storage_class_content_regions_json_array;
-- rollback alter table posts drop constraint if exists chk_posts_visibility_regions_json_array;
-- rollback alter table media drop constraint if exists chk_media_visibility_regions_json_array;
-- rollback drop index if exists idx_storage_class_single_primary;
-- rollback drop index if exists idx_media_storage_classes_gin;
