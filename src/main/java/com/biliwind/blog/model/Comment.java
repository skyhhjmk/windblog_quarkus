package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 评论实体，对应 comments
 */
@Entity
@Table(name = "comments")
public class Comment extends PanacheEntityBase {
    public static final short AUDIT_STATUS_PENDING = 0;
    public static final short AUDIT_STATUS_REVIEWING = 1;
    public static final short AUDIT_STATUS_APPROVED = 2;
    public static final short AUDIT_STATUS_REJECTED = 3;
    public static final short AUDIT_STATUS_FAILED = 4;

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属文章 */
    @ManyToOne(fetch = FetchType.LAZY)
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

    /** Imported or guest display identity when no account can be safely linked. */
    @Column(name = "guest_name", length = 255)
    public String guestName;

    @Column(name = "guest_email", length = 255)
    public String guestEmail;

    @Column(name = "guest_website", length = 2048)
    public String guestWebsite;

    @Column(name = "client_os", length = 32)
    public String clientOs;

    @Column(name = "client_browser", length = 32)
    public String clientBrowser;

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
     * 审核状态：0=未审核 1=审核中 2=审核通过 3=审核拒绝 4=审核失败
     */
    @Column(name = "audit_status", nullable = false)
    public short auditStatus;

    /**
     * AI 审核数据
     */
    @Column(name = "ai_review_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Object aiReviewData;

    /**
     * 是否正在审核中
     */
    @Column(name = "is_reviewing", nullable = false)
    public boolean isReviewing = false;
}
