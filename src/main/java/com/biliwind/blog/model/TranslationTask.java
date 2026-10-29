package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Durable queue row for asynchronous article translation. */
@Entity
@Table(name = "translation_tasks")
public class TranslationTask extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "post_id", nullable = false)
    public Long postId;

    @Column(name = "target_language", nullable = false, length = 32)
    public String targetLanguage;

    @Column(name = "source_language", length = 32)
    public String sourceLanguage;

    @Column(nullable = false)
    public boolean overwrite;

    @Column(nullable = false, length = 24)
    public String status = "QUEUED";

    @Column(columnDefinition = "text")
    public String message;

    @Column(name = "revision_number")
    public Integer revisionNumber;

    @Column(nullable = false)
    public boolean published;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "max_attempts", nullable = false)
    public int maxAttempts = 3;

    @Column(name = "next_attempt_at")
    public OffsetDateTime nextAttemptAt;

    @Column(name = "batch_id", length = 64)
    public String batchId;

    @Column(name = "created_by")
    public Long createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "started_at")
    public OffsetDateTime startedAt;

    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
