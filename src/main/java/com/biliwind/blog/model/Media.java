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
 * 媒体资源实体，对应 media
 */
@Entity
@Table(name = "media")
public class Media extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 存储系统中的资源键 */
    @Column(name = "storage_key", nullable = false, length = 255)
    public String storageKey;

    /** 资源访问URL */
    @Column(nullable = false, columnDefinition = "text")
    public String url;

    /** 媒体类型：0图片，1视频，2文件 */
    @Column(name = "media_type", nullable = false)
    public short mediaType;

    /** MIME类型 */
    @Column(name = "mime_type", length = 100)
    public String mimeType;

    @Column(name = "file_name", length = 255)
    public String fileName;

    /**
     * 文件大小（字节）
     */
    @Column
    public Long size;

    @Column(name = "uploaded_by")
    public Long uploadedBy;

    /** 媒体宽度 */
    @Column
    public Integer width;

    /** 媒体高度 */
    @Column
    public Integer height;

    /** 多语言alt文本（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, String> alt;

    /** 扩展元数据（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> metadata;

    /**
     * 各存储提供者的状态矩阵
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storage_providers", columnDefinition = "jsonb")
    public Map<String, Object> storageProviders;

    /**
     * 乐观锁版本号
     */
    @Column
    public Integer version;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;


    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;

    /**
     * 处理状态：PENDING, PROCESSING, COMPLETED, FAILED
     */
    @Column(name = "processing_status", length = 50)
    public String processingStatus;

    /**
     * 处理进度百分比 (0-100)
     */
    @Column(name = "processing_progress")
    public Integer processingProgress;

    /**
     * 处理失败时的详细错误信息
     */
    @Column(name = "processing_error", columnDefinition = "text")
    public String processingError;

    /**
     * 区域可见性设置 (JSON数组)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visibility_regions", columnDefinition = "jsonb")
    public java.util.List<String> visibilityRegions;

    /**
     * @return size
     */
    public Long getSize() {
        return size;
    }
}
