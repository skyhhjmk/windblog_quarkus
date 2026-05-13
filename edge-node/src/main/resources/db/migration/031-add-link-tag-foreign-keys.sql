-- liquibase formatted sql

-- changeset biliwind:031-add-link-tag-foreign-keys
alter table link_tag_relations
    add constraint fk_link_tag_relations_link
        foreign key (link_id) references links (id) on delete cascade;

alter table link_tag_relations
    add constraint fk_link_tag_relations_tag
        foreign key (tag_id) references link_tags (id) on delete cascade;

-- rollback alter table link_tag_relations drop constraint fk_link_tag_relations_tag;
-- rollback alter table link_tag_relations drop constraint fk_link_tag_relations_link;
