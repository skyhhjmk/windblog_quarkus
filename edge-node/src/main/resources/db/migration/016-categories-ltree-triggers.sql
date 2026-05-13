-- liquibase formatted sql

-- changeset biliwind:016-categories-ltree-triggers splitStatements:false
-- description: Add trigger to auto-generate ltree path and prevent cyclic hierarchy

create or replace function categories_set_path()
    returns trigger as
$$
declare
    parent_path  ltree;
    current_path ltree;
begin
    -- Root node
    if new.parent_id is null then
        new.path := new.slug::ltree;
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

    new.path := parent_path || new.slug::ltree;

    return new;
end;
$$ language plpgsql;

drop trigger if exists trg_categories_set_path on categories;

create trigger trg_categories_set_path
    before insert or update of parent_id, slug
    on categories
    for each row
execute function categories_set_path();

-- rollback
-- rollback drop trigger if exists trg_categories_set_path on categories;
-- rollback drop function if exists categories_set_path();
