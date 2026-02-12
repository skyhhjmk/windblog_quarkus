package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 文章与媒体关联实体，对应 post_media
 */
@Entity
@Table(name = "post_media")
public class PostMedia extends PanacheEntityBase {

    /** 联合主键 */
    @EmbeddedId
    public PostMediaId id;

    /** 文章 */
    @MapsId("postId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    /** 媒体 */
    @MapsId("mediaId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    public Media media;

    /** 用途：正文/特色图/画廊/OG图 */
    @Column(name = "usage_type", nullable = false)
    public short usageType;

    /** 排序位置 */
    @Column
    public Integer position;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
