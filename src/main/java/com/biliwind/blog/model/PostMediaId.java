package com.biliwind.blog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * 文章与媒体关联表联合主键
 */
@Embeddable
public class PostMediaId implements Serializable {

    /** 文章ID */
    @Column(name = "post_id")
    public Long postId;

    /** 媒体ID */
    @Column(name = "media_id")
    public Long mediaId;

    /** JPA默认构造器 */
    public PostMediaId() {
    }

    /**
     * 构造联合主键
     *
     * @param postId 文章ID
     * @param mediaId 媒体ID
     */
    public PostMediaId(Long postId, Long mediaId) {
        this.postId = postId;
        this.mediaId = mediaId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PostMediaId that)) {
            return false;
        }
        return Objects.equals(postId, that.postId) && Objects.equals(mediaId, that.mediaId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, mediaId);
    }
}
