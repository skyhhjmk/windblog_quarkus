package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 评论实体，对应 comments
 */
@Entity
@Table(name = "comments")
public class Comment extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属文章 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    /** 父评论 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    public Comment parent;

    /** 评论用户 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    public User user;

    /** 评论内容 */
    @Column(nullable = false, columnDefinition = "text")
    public String content;

    /** 评论状态：0待审核，1通过，2垃圾 */
    @Column(nullable = false)
    public short status;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;


    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;

    /**
     * 审核状态：0=未审核 1=审核中 2=审核通过 3=审核拒绝
     */
    @Column(name = "audit_status", nullable = false)
    public short auditStatus;

    /**
     * 审核类型：0=无 1=AI 审核 2=人工审核
     */
    @Column(name = "audit_type", nullable = false)
    public short auditType;

    /**
     * 审核原因/理由
     */
    @Column(name = "audit_reason", columnDefinition = "text")
    public String auditReason;

    /**
     * AI 审核耗时
     */
    @Column(name = "ai_duration_ms")
    public Long aiDurationMs;

    /**
     * AI 审核消耗 Token
     */
    @Column(name = "ai_total_tokens")
    public Integer aiTotalTokens;

    /**
     * AI 审核评分 (0-100)
     */
    @Column(name = "ai_score")
    public Integer aiScore;
}
