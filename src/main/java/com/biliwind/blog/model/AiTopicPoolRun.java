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

/** Asynchronous topic discovery run for the WindBlog-owned topic pool. */
@Entity
@Table(name = "ai_topic_pool_runs")
public class AiTopicPoolRun extends PanacheEntityBase implements AiRunRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "trigger_type", nullable = false, length = 32)
    public String triggerType = "MANUAL";

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @Column(name = "provider_id")
    public Long providerId;

    @Column(name = "seed_count", nullable = false)
    public int seedCount;

    @Column(name = "topic_count", nullable = false)
    public int topicCount;

    @Column(name = "error_message", columnDefinition = "text")
    public String errorMessage;

    @Column(name = "trace_id", length = 64)
    public String traceId;

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
