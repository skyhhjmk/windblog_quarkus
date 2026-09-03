package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** Durable idempotency record for a signed Codex-to-WindBlog content call. */
@Entity
@Table(name = "codex_creator_content_operations")
public class CodexCreatorContentOperation extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "request_key", nullable = false, unique = true, length = 256)
    public String requestKey;

    @Column(nullable = false, length = 64)
    public String operation;

    @Column(name = "request_digest", nullable = false, length = 64)
    public String requestDigest;

    @Column(nullable = false, length = 32)
    public String status = "PROCESSING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String response;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;
}
