package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 上传权限角色
 */
@Entity
@Table(name = "upload_roles")
public class UploadRole extends PanacheEntityBase {

    /**
     * 角色名
     */
    @Id
    @Column(length = 64)
    public String name;

    /**
     * 角色显示名称
     */
    @Column(name = "display_name", length = 128)
    public String displayName;

    /**
     * 角色描述
     */
    @Column(columnDefinition = "text")
    public String description;

    /**
     * 是否允许上传文件
     */
    @Column(name = "can_upload", nullable = false)
    public boolean canUpload;

    /**
     * 允许的 MIME 类型
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_mime_types", columnDefinition = "jsonb")
    public List<String> allowedMimeTypes;

    /**
     * 单个文件最大上传字节数
     */
    @Column(name = "max_single_upload_bytes")
    public Long maxSingleUploadBytes;

    /**
     * 总文件最大上传字节数
     */
    @Column(name = "max_total_upload_bytes")
    public Long maxTotalUploadBytes;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
