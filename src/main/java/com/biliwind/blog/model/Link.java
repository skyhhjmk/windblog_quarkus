package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 链接主表实体，对应 links。
 */
@Entity
@Table(name = "links")
public class Link extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 链接名称 */
    @Column(nullable = false, columnDefinition = "text")
    public String name;

    /** 链接地址（唯一） */
    @Column(nullable = false, unique = true, columnDefinition = "text")
    public String url;

    /**
     * 前台跳转使用的随机公开 token
     */
    @Column(name = "public_token", length = 64, unique = true)
    public String publicToken;

    /** 链接描述 */
    @Column(columnDefinition = "text")
    public String description;

    /** 配图URL */
    @Column(columnDefinition = "text")
    public String image;

    /** 图标URL */
    @Column(columnDefinition = "text")
    public String icon;

    /** 排序权重，越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder;

    /**
     * 链接类型
     */
    @Convert(converter = LinkTypeConverter.class)
    @Column(nullable = false)
    public LinkType type;

    /** 状态：1=visible, 2=hidden, 3=archived */
    @Column(nullable = false)
    public short status;

    /**
     * 申请状态：1=已通过，2=待审核，3=已拒绝
     */
    @Column(name = "application_status", nullable = false)
    public short applicationStatus;

    /**
     * 多节点聚合可用状态：UNKNOWN、ONLINE、OFFLINE
     */
    @Column(name = "availability_status", nullable = false, length = 16)
    public String availabilityStatus;

    /**
     * 最近一次成功访问结果中的反链状态：UNKNOWN、FOUND、MISSING
     */
    @Column(name = "backlink_status", nullable = false, length = 16)
    public String backlinkStatus;

    /** 最近一次检测到的关键词欺诈状态：UNKNOWN、CLEAN、DETECTED */
    @Column(name = "keyword_fraud_status", nullable = false, length = 16)
    public String keywordFraudStatus = "UNKNOWN";

    /**
     * 最近一次多节点监控完成时间
     */
    @Column(name = "last_checked_at")
    public OffsetDateTime lastCheckedAt;

    /** 打开方式，如 _blank、_self */
    @Column(nullable = false, columnDefinition = "text")
    public String target;

    /** 跳转方式：1=direct, 2=goto, 3=iframe, 4=info */
    @Column(name = "redirect_type", nullable = false)
    public short redirectType;

    /** 是否显示原始URL */
    @Column(name = "show_url", nullable = false)
    public boolean showUrl;

    /** 详细内容 */
    @Column(columnDefinition = "text")
    public String content;

    /** 联系邮箱 */
    @Column(columnDefinition = "text")
    public String email;

    /** 回调地址 */
    @Column(name = "callback_url", columnDefinition = "text")
    public String callbackUrl;

    /** 备注 */
    @Column(columnDefinition = "text")
    public String note;

    /** SEO 标题 */
    @Column(name = "seo_title", columnDefinition = "text")
    public String seoTitle;

    /** SEO 关键词 */
    @Column(name = "seo_keywords", columnDefinition = "text")
    public String seoKeywords;

    /** SEO 描述 */
    @Column(name = "seo_description", columnDefinition = "text")
    public String seoDescription;

    /** 扩展设置(JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> settings;

    /** Public status badge derived from the current auto-hide grace period. */
    @Transient
    public String publicMonitorLabel;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /**
     * 关联的标签
     */
    @OneToMany(mappedBy = "link", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<LinkTagRelation> tagRelations;
}
