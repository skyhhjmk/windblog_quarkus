package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 上传权限角色
 */
@Entity
@Table(name = "upload_roles")
public class UploadRole extends PanacheEntityBase {

    @Id
    @Column(length = 64)
    public String name;

    @Column(name = "display_name", length = 128)
    public String displayName;

    @Column(columnDefinition = "text")
    public String description;

    @Column(name = "can_upload", nullable = false)
    public boolean canUpload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_mime_types", columnDefinition = "jsonb")
    public List<String> allowedMimeTypes;

    @Column(name = "max_single_upload_bytes")
    public Long maxSingleUploadBytes;

    @Column(name = "max_total_upload_bytes")
    public Long maxTotalUploadBytes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
