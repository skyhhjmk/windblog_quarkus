package com.biliwind.blog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * 文章与标签关联表联合主键
 */
@Embeddable
public class PostTagId implements Serializable {

    /**
     * 文章ID
     */
    @Column(name = "post_id")
    public Long postId;

    /**
     * 标签ID
     */
    @Column(name = "tag_id")
    public Long tagId;

    /**
     * JPA默认构造器
     */
    public PostTagId() {
    }

    /**
     * 构造联合主键
     *
     * @param postId 文章ID
     * @param tagId  标签ID
     */
    public PostTagId(Long postId, Long tagId) {
        this.postId = postId;
        this.tagId = tagId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PostTagId that)) {
            return false;
        }
        return Objects.equals(postId, that.postId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, tagId);
    }
}
