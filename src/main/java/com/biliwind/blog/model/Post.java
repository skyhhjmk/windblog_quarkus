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
 * 文章主表实体，对应 posts
 */
@Entity
@Table(name = "posts")
public class Post extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 唯一文章标识 */
    @Column(nullable = false, length = 160, unique = true)
    public String slug;

    /** 多语言标题（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public Map<String, String> title;

    /** 多语言摘要（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, String> summary;

    /** 多语言AI摘要（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_summary", columnDefinition = "jsonb")
    public Map<String, String> aiSummary;

    /**
     * AI摘要状态：0-自动，1-冻结，2-关闭
     */
    @Column(name = "ai_summary_status", nullable = false)
    public Short aiSummaryStatus = 0;

    /** 当前生效版本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_revision_id")
    public PostRevision currentRevision;

    /** 文章状态：参考 `PostStatus` 枚举 */
    @Column(nullable = false)
    public PostStatus status;

    /** 可见性：0公开，1私密，2密码 */
    @Column(nullable = false)
    public short visibility;

    /** 访问密码 */
    @Column(length = 100)
    public String password;

    /** SEO 标题 */
    @Column(name = "seo_title")
    public String seoTitle;

    /** SEO 关键词 */
    @Column(name = "seo_keywords")
    public String seoKeywords;

    /** SEO 描述 */
    @Column(name = "seo_description", columnDefinition = "text")
    public String seoDescription;


    /**
     * 前端渲染器类型
     */
    @Convert(converter = PostRenderTypeConverter.class)
    @Column(name = "render_type", nullable = false)
    public PostRenderType renderType;

    /** 作者 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    /**
     * 所属分类
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    public Category category;

    /** 发布时间 */
    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;

    /**
     * 阅读数量
     */
    @Column(name = "view_count", nullable = false)
    public Long viewCount = 0L;

    /**
     * 是否为特色文章
     */
    @Column(nullable = false)
    public Boolean featured = false;

    /**
     * 是否允许评论
     */
    @Column(name = "allow_comment", nullable = false)
    public Boolean allowComment = true;

    /**
     * 扩展元数据（如买断价格等）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_info", columnDefinition = "jsonb")
    public Object extraInfo;

    /** 乐观锁版本号 */
    @Version
    @Column(nullable = false)
    public Integer version;
}
