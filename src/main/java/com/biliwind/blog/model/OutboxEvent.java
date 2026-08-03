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
import java.util.Map;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 160)
    public String eventKey;

    @Column(name = "event_type", nullable = false, length = 80)
    public String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    public String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 80)
    public String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> payload;

    @Column(nullable = false, length = 24)
    public String status = "PENDING";

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "available_at", nullable = false)
    public OffsetDateTime availableAt;

    @Column(name = "locked_until")
    public OffsetDateTime lockedUntil;

    @Column(name = "lock_owner", length = 80)
    public String lockOwner;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "trace_id", length = 128)
    public String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
}
