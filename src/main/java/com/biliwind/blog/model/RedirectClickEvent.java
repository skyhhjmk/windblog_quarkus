package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * go 短链点击证据链，对应 redirect_click_events。
 */
@Entity
@Table(name = "redirect_click_events")
public class RedirectClickEvent extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 幂等事件键
     */
    @Column(name = "event_key", nullable = false, length = 80, unique = true)
    public String eventKey;

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
     * Referer 原文
     */
    @Column(name = "referer_url", columnDefinition = "text")
    public String refererUrl;

    /**
     * Referer 域名
     */
    @Column(name = "referer_domain", length = 255)
    public String refererDomain;

    /**
     * Referer 分类
     */
    @Column(name = "referer_category", nullable = false, length = 40)
    public String refererCategory;

    /**
     * IP 哈希
     */
    @Column(name = "ip_hash", length = 128)
    public String ipHash;

    /**
     * UA 哈希
     */
    @Column(name = "user_agent_hash", length = 128)
    public String userAgentHash;

    /**
     * 风险设备 ID
     */
    @Column(name = "device_risk_id", length = 128)
    public String deviceRiskId;

    /**
     * 风险评分
     */
    @Column(name = "risk_score", nullable = false)
    public int riskScore;

    /**
     * 来源节点 ID
     */
    @Column(name = "source_node_id", length = 100)
    public String sourceNodeId;

    /**
     * 点击时间
     */
    @Column(name = "clicked_at", nullable = false)
    public OffsetDateTime clickedAt;

    /**
     * 是否已回传主节点
     */
    @Column(name = "synced_to_primary", nullable = false)
    public boolean syncedToPrimary = false;
}
