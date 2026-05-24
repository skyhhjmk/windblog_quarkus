package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 转载授权记录，对应 repost_licenses。
 */
@Entity
@Table(name = "repost_licenses")
public class RepostLicense extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 授权码
     */
    @Column(nullable = false, length = 64, unique = true)
    public String code;

    /**
     * 被转载文章
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    public Post article;

    /**
     * 申请转载的用户
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "viewer_user_id", nullable = false)
    public User viewerUser;

    /**
     * 授权转载域名
     */
    @Column(name = "allowed_domain", nullable = false, length = 255)
    public String allowedDomain;

    /**
     * 用户提交的转载页面 URL
     */
    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    public String targetUrl;

    /**
     * 状态：1有效，2撤销
     */
    @Column(nullable = false)
    public short status = 1;

    /**
     * 语义水印配置
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "semantic_watermark_config", columnDefinition = "jsonb")
    public Map<String, Object> semanticWatermarkConfig;

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
