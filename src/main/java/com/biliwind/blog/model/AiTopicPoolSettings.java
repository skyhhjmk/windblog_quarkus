package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Automation settings for the WindBlog-owned topic pool. */
@Entity
@Table(name = "ai_topic_pool_settings")
public class AiTopicPoolSettings extends PanacheEntityBase {
    @Id
    public Long id = 1L;

    @Column(nullable = false)
    public boolean enabled;

    @Column(name = "interval_minutes", nullable = false)
    public int intervalMinutes = 360;

    @Column(name = "max_seeds_per_run", nullable = false)
    public int maxSeedsPerRun = 5;

    @Column(name = "max_topics_per_run", nullable = false)
    public int maxTopicsPerRun = 20;

    @Column(name = "promotion_enabled", nullable = false)
    public boolean promotionEnabled;

    @Column(name = "promotion_markdown", columnDefinition = "text")
    public String promotionMarkdown;

    @Column(name = "last_run_at")
    public OffsetDateTime lastRunAt;

    @Column(name = "last_success_at")
    public OffsetDateTime lastSuccessAt;

    @Column(name = "last_error", columnDefinition = "text")
    public String lastError;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
