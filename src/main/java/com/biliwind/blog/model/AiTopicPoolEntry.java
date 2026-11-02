package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** WindBlog-owned topic entry used when the topic operation is served by a normal AI provider. */
@Entity
@Table(name = "ai_topic_pool")
public class AiTopicPoolEntry extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, columnDefinition = "text")
    public String title;

    @Column(columnDefinition = "text")
    public String rationale;

    @Column(nullable = false, length = 32)
    public String recommendation = "MANUAL";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    public String source = "{}";

    @Column(nullable = false, length = 32)
    public String status = "SUGGESTED";

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 64)
    public String dedupeKey;

    @Column(name = "occurrence_count", nullable = false)
    public int occurrenceCount = 1;

    @Column(name = "review_note", columnDefinition = "text")
    public String reviewNote;

    @Column(name = "post_id")
    public Long postId;

    @Column(name = "last_seen_at", nullable = false)
    public OffsetDateTime lastSeenAt;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
