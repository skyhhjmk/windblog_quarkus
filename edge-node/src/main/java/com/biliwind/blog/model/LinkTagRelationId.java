package com.biliwind.blog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * 链接与标签关联表联合主键
 */
@Embeddable
public class LinkTagRelationId implements Serializable {

    /**
     * 链接ID
     */
    @Column(name = "link_id")
    public Long linkId;

    /**
     * 标签ID
     */
    @Column(name = "tag_id")
    public Long tagId;

    /**
     * JPA默认构造器
     */
    public LinkTagRelationId() {
    }

    /**
     * 构造联合主键
     *
     * @param linkId 链接ID
     * @param tagId  标签ID
     */
    public LinkTagRelationId(Long linkId, Long tagId) {
        this.linkId = linkId;
        this.tagId = tagId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LinkTagRelationId that)) {
            return false;
        }
        return Objects.equals(linkId, that.linkId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(linkId, tagId);
    }
}
