package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** WindBlog-side idempotency and ownership record for an AI-created draft. */
@Entity
@Table(name = "codex_creator_draft_assignments")
public class CodexCreatorDraftAssignment extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "topic_id", nullable = false)
    public Long topicId;

    @Column(name = "category_id")
    public Long categoryId;

    @Column(nullable = false, length = 16)
    public String language = "zh-CN";

    @Column(columnDefinition = "text")
    public String instructions;

    @Column(name = "request_key", nullable = false, unique = true, length = 64)
    public String requestKey;

    @Column(name = "created_by", nullable = false)
    public Long createdBy;

    @Column(name = "codex_job_id")
    public Long codexJobId;

    @Column(name = "codex_task_id")
    public Long codexTaskId;

    @Column(name = "post_id")
    public Long postId;

    @Column(nullable = false, length = 32)
    public String status = "REQUESTED";

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
