package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "wesp_sync_manifests")
public class WespSyncManifest extends PanacheEntityBase {
    @Id
    @Column(name = "manifest_id", length = 64)
    public String manifestId;

    @Column(name = "media_id", nullable = false, length = 128)
    public String mediaId;

    @Column(name = "variant", nullable = false, length = 32)
    public String variant;

    @Column(name = "file_hash", nullable = false, length = 64)
    public String fileHash;

    @Column(name = "file_size", nullable = false)
    public long fileSize;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    public String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
