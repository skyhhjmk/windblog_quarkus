package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 文章 Markdown 外链引用关系，对应 link_article_references。
 */
@Entity
@Table(name = "link_article_references")
public class LinkArticleReference extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 被引用的链接
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    public Link link;

    /**
     * 引用该链接的文章
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    /**
     * Markdown 链接文本
     */
    @Column(name = "anchor_text", columnDefinition = "text")
    public String anchorText;

    /**
     * 规范化后的链接地址
     */
    @Column(name = "normalized_url", nullable = false, columnDefinition = "text")
    public String normalizedUrl;

    /**
     * 同一文章中引用同一链接的次数
     */
    @Column(name = "reference_count", nullable = false)
    public Integer referenceCount = 1;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
