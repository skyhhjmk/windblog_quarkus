package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 文章版本实体，对应 post_revisions
 * <p>
 * 每个版本一经创建不应该被修改，故不包含更新时间字段
 */
@Entity
@Table(name = "post_revisions")
public class PostRevision extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属文章 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    /** 版本标题（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public Map<String, String> title;

    /** Markdown正文（JSONB，多语言） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_markdown", columnDefinition = "jsonb", nullable = false)
    public Map<String, String> contentMarkdown;

    /** 各语言段落级翻译与源Hash映射 (JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paragraph_translations", columnDefinition = "jsonb")
    public Map<String, com.biliwind.blog.model.translation.ParagraphTranslationMeta> paragraphTranslations;

    /**
     * Edge sync provenance: when true, {@link #contentMarkdown} already holds
     * the sanitized public preview rendered by the primary. Only the sync
     * apply path sets this, so rendering decisions never depend on content.
     */
    @Column(name = "public_preview_rendered", nullable = false)
    public boolean publicPreviewRendered;


    /** 编辑器类型：0Markdown，1HTML，2BlockEditor */
    @Column(name = "editor_type", nullable = false)
    public short editorType;
    // TODO 这个 BlockEditor 我貌似从来都没做出过

    /** 版本号（同一文章内递增） */
    @Column(name = "revision_number", nullable = false)
    public int revisionNumber;

    /** 创建人 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    public User createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
