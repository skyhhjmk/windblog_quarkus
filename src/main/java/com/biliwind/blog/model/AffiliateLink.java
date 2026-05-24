package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 商业或 affiliate 原始目标链接，对应 affiliate_links。
 */
@Entity
@Table(name = "affiliate_links")
public class AffiliateLink extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 链接名称
     */
    @Column(nullable = false, length = 200)
    public String name;

    /**
     * 原始目标 URL
     */
    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    public String targetUrl;

    /**
     * 原始目标域名
     */
    @Column(name = "target_domain", nullable = false, length = 255)
    public String targetDomain;

    /**
     * 状态：1启用，2停用
     */
    @Column(nullable = false)
    public short status = 1;

    /**
     * 备注
     */
    @Column(columnDefinition = "text")
    public String note;

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
