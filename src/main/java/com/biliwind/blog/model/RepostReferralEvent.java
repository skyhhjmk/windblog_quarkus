package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 转载页原文地址 GET 访问追踪事件，对应 repost_referral_events。
 */
@Entity
@Table(name = "repost_referral_events")
public class RepostReferralEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repost_license_id", nullable = false)
    public RepostLicense repostLicense;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    public Post article;

    @Column(name = "license_code", nullable = false, length = 64)
    public String licenseCode;

    @Column(name = "referer_url", columnDefinition = "text")
    public String refererUrl;

    @Column(name = "referer_domain", length = 255)
    public String refererDomain;

    @Column(name = "referer_category", nullable = false, length = 40)
    public String refererCategory = "unknown_referrer";

    @Column(name = "ip_hash", length = 128)
    public String ipHash;

    @Column(name = "user_agent_hash", length = 128)
    public String userAgentHash;

    @Column(name = "source_node_id", length = 100)
    public String sourceNodeId;

    @Column(name = "viewed_at", nullable = false)
    public OffsetDateTime viewedAt = OffsetDateTime.now();
}
