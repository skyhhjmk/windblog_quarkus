package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 评论引用/纠错关联实体，对应 comment_quotes
 */
@Entity
@Table(name = "comment_quotes")
public class CommentQuote extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 关联的评论
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id", nullable = false)
    public Comment comment;

    /**
     * 关联的文章
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    /**
     * 引用类型：'QUOTE' (普通引用), 'CORRECTION' (纠错)
     */
    @Column(name = "quote_type", nullable = false, length = 20)
    public String quoteType;

    /**
     * 当时被引用的文本原文
     */
    @Column(name = "quote_text", nullable = false, columnDefinition = "text")
    public String quoteText;

    /**
     * 锚点定位元数据（JSONB: prefix, suffix, exact 等）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anchor_data", nullable = false, columnDefinition = "jsonb")
    public Object anchorData;

    /**
     * 纠错状态：0=待处理，1=已采纳，2=已驳回
     */
    @Column(nullable = false)
    public short status = 0;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
