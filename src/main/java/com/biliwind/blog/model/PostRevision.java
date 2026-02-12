package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 文章版本实体，对应 post_revisions
 */
@Entity
@Table(name = "post_revisions")
public class PostRevision extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 所属文章 */
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

    /** 编辑器类型：0Markdown，1HTML */
    @Column(name = "editor_type", nullable = false)
    public short editorType;

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
