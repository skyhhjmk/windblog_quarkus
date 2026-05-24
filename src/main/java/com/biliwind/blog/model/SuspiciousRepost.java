package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 可疑转载聚合记录，对应 suspicious_reposts。
 */
@Entity
@Table(name = "suspicious_reposts")
public class SuspiciousRepost extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 命中的 token
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "affiliate_token_id", nullable = false)
    public AffiliateToken affiliateToken;

    /**
     * 关联转载授权
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repost_license_id")
    public RepostLicense repostLicense;

    /**
     * 关联文章
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    public Post article;

    /**
     * 可疑域名
     */
    @Column(name = "suspicious_domain", nullable = false, length = 255)
    public String suspiciousDomain;

    /**
     * 首次 Referer
     */
    @Column(name = "first_referer_url", columnDefinition = "text")
    public String firstRefererUrl;

    /**
     * 最近 Referer
     */
    @Column(name = "latest_referer_url", columnDefinition = "text")
    public String latestRefererUrl;

    /**
     * 点击次数
     */
    @Column(name = "click_count", nullable = false)
    public long clickCount = 1;

    /**
     * 风险评分
     */
    @Column(name = "risk_score", nullable = false)
    public int riskScore;

    /**
     * 检测证据
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    public Map<String, Object> evidenceJson;

    /**
     * 首次发现时间
     */
    @Column(name = "first_seen_at", nullable = false)
    public OffsetDateTime firstSeenAt;

    /**
     * 最近发现时间
     */
    @Column(name = "last_seen_at", nullable = false)
    public OffsetDateTime lastSeenAt;
}
