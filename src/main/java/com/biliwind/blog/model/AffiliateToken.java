package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * go 短链 token 状态表，对应 affiliate_tokens。
 */
@Entity
@Table(name = "affiliate_tokens")
public class AffiliateToken extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * token 原文的哈希值
     */
    @Column(name = "token_hash", nullable = false, length = 128, unique = true)
    public String tokenHash;

    /**
     * 后台展示用短值
     */
    @Column(name = "short_display", nullable = false, length = 32)
    public String shortDisplay;

    /**
     * token 类型：FIRST_PARTY、REPOST_LICENSE
     */
    @Column(name = "token_type", nullable = false, length = 32)
    public String tokenType;

    /**
     * 状态：1有效，2撤销
     */
    @Column(nullable = false)
    public short status = 1;

    /**
     * 关联文章
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    public Post article;

    /**
     * 关联商业链接
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "affiliate_link_id")
    public AffiliateLink affiliateLink;

    /**
     * 关联用户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viewer_user_id")
    public User viewerUser;

    /**
     * 关联转载授权
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repost_license_id")
    public RepostLicense repostLicense;

    /**
     * 允许出现 token 的域名
     */
    @Column(name = "allowed_domain", length = 255)
    public String allowedDomain;

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

    /**
     * 撤销时间
     */
    @Column(name = "revoked_at")
    public OffsetDateTime revokedAt;
}
