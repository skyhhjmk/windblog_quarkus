package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_provider_configs")
public class AiProviderConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public AiConfigType type = AiConfigType.PROVIDER;

    @Column(nullable = false, unique = true, length = 64)
    public String name;

    @Column(nullable = false, length = 64)
    public String provider;

    @Column(nullable = false)
    public boolean enabled;

    @Column(length = 512)
    public String endpoint;

    @Column(name = "api_key", length = 512)
    public String apiKey;

    @Column(length = 128)
    public String model;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public String config;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
