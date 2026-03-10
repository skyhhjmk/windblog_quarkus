package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * 链接与标签关联实体，对应 link_tag_relations
 */
@Entity
@Table(name = "link_tag_relations")
public class LinkTagRelation extends PanacheEntityBase {

    /**
     * 联合主键
     */
    @EmbeddedId
    public LinkTagRelationId id;

    /**
     * 链接
     */
    @MapsId("linkId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    public Link link;

    /**
     * 标签
     */
    @MapsId("tagId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    public LinkTag tag;
}
