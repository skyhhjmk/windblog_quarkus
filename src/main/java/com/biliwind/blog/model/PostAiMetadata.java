package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** System-level AI provenance; it is intentionally independent of post categories. */
@Entity
@Table(name = "post_ai_metadata")
public class PostAiMetadata extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "post_id", nullable = false)
    public Long postId;

    @Column(name = "revision_id")
    public Long revisionId;

    @Column(name = "task_id", length = 128)
    public String taskId;

    @Column(nullable = false, length = 80)
    public String operation;

    @Column(nullable = false, length = 80)
    public String provider;

    @Column(name = "model_id", length = 160)
    public String modelId;

    @Column(name = "reasoning_effort", length = 32)
    public String reasoningEffort;

    @Column(name = "generation_mode", nullable = false, length = 32)
    public String generationMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provenance", columnDefinition = "jsonb", nullable = false)
    public String provenance = "{}";

    @Column(name = "auto_publish_status", nullable = false, length = 32)
    public String autoPublishStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
}
