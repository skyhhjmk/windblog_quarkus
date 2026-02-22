package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
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

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;

    /**
     * @return size
     */
    public Long getSize() {
        return size;
    }
}
