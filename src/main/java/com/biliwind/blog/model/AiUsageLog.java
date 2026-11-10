package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_usage_logs")
public class AiUsageLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "provider_config_id")
    public Long providerConfigId;

    @Column(name = "provider_name", nullable = false, length = 64)
    public String providerName;

    @Column(name = "provider_type", nullable = false, length = 64)
    public String providerType;

    @Column(length = 128)
    public String model;

    @Column(nullable = false, length = 64)
    public String feature;

    @Column(name = "prompt_tokens", nullable = false)
    public int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    public int completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    public int totalTokens = 0;

    @Column(name = "duration_ms", nullable = false)
    public long durationMs = 0;

    @Column(nullable = false)
    public boolean success = true;

    @Column(name = "error_message", length = 1024)
    public String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
