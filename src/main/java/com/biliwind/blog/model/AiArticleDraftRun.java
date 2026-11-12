package com.biliwind.blog.model;

import com.biliwind.blog.service.ai.AiRunRecord;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Asynchronous AI-provider article draft generation for a topic. */
@Entity
@Table(name = "ai_article_draft_runs")
public class AiArticleDraftRun extends PanacheEntityBase implements AiRunRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "topic_id", nullable = false)
    public Long topicId;

    @Column(name = "post_id")
    public Long postId;

    @Column(name = "provider_id")
    public Long providerId;

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @Column(nullable = false, length = 32)
    public String language = "zh-CN";

    @Column(columnDefinition = "text")
    public String instructions;

    @Column(name = "repost_policy_code", length = 64)
    public String repostPolicyCode;

    @Column(nullable = false)
    public boolean regenerate;

    @Column(length = 512)
    public String title;

    @Column(columnDefinition = "text")
    public String summary;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "created_by")
    public Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "started_at")
    public OffsetDateTime startedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;

    @Override
    public String runStatus() {
        return status;
    }

    @Override
    public OffsetDateTime runCreatedAt() {
        return createdAt;
    }

    @Override
    public OffsetDateTime runStartedAt() {
        return startedAt;
    }

    @Override
    public void markRunFailed(String message, OffsetDateTime now) {
        status = "FAILED";
        errorMessage = message;
        completedAt = now;
    }

    @Override
    public void persistRun() {
        persist();
    }
}
