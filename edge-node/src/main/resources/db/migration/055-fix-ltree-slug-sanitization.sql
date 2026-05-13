-- liquibase formatted sql

-- changeset biliwind:055-fix-ltree-slug-sanitization splitStatements:false
-- description: Sanitize slug by replacing hyphens with underscores before casting to ltree

create or replace function categories_set_path()
    returns trigger as
$$
declare
    parent_path    ltree;
    current_path   ltree;
    sanitized_slug text;
begin
    -- Sanitize slug for ltree (letters, numbers, underscores only)
    sanitized_slug := replace(new.slug, '-', '_');

    -- Root node
    if new.parent_id is null then
        new.path := sanitized_slug::ltree;
        return new;
    end if;

    -- Prevent self-parent
    if new.id is not null and new.id = new.parent_id then
        raise exception 'Category cannot be its own parent';
    end if;

    -- Get parent path
    select path
    into parent_path
    from categories
    where id = new.parent_id;

    if parent_path is null then
        raise exception 'Parent category not found';
    end if;

    -- Cycle detection
    if new.id is not null then
        select path
        into current_path
        from categories
        where id = new.id;

        if current_path is not null and parent_path <@ current_path then
            raise exception 'Cyclic category hierarchy detected';
        end if;
    end if;

    new.path := parent_path || sanitized_slug::ltree;

    return new;
end;
$$ language plpgsql;

-- rollback create or replace function categories_set_path()
-- rollback     returns trigger as $$
-- rollback declare
-- rollback     parent_path ltree;
-- rollback     current_path ltree;
-- rollback begin
-- rollback     if new.parent_id is null then
-- rollback         new.path := new.slug::ltree;
-- rollback         return new;
-- rollback     end if;
-- rollback     if new.id is not null and new.id = new.parent_id then
-- rollback         raise exception 'Category cannot be its own parent';
-- rollback     end if;
-- rollback     select path into parent_path
-- rollback     from categories
-- rollback     where id = new.parent_id;
-- rollback     if parent_path is null then
-- rollback         raise exception 'Parent category not found';
-- rollback     end if;
-- rollback     if new.id is not null then
-- rollback         select path into current_path
-- rollback         from categories
-- rollback         where id = new.id;
-- rollback         if current_path is not null and parent_path <@ current_path then
-- rollback             raise exception 'Cyclic category hierarchy detected';
-- rollback         end if;
-- rollback     end if;
-- rollback     new.path := parent_path || new.slug::ltree;
-- rollback     return new;
-- rollback end;
-- rollback $$ language plpgsql;
