package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "storage_provider")
public class StorageProviderEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 50)
    public String name;

    @Column(name = "display_name", nullable = false, length = 100)
    public String displayName;

    @Column(name = "provider_type", nullable = false, length = 20)
    public String providerType;

    @Column(name = "is_enabled", nullable = false)
    public Boolean isEnabled = true;

    @Column(name = "is_primary", nullable = false)
    public Boolean isPrimary = false;

    @Column(nullable = false, length = 20)
    public String role = "backup";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    public String configJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_types", nullable = false, columnDefinition = "jsonb")
    public String supportedTypes = "[\"*\"]";

    @Column(name = "cdn_domain")
    public String cdnDomain;

    @Column(name = "cdn_enabled", nullable = false)
    public Boolean cdnEnabled = false;

    @Column(nullable = false, length = 20)
    public BlogRegion region = BlogRegion.GLOBAL;

    @Column(nullable = false)
    public Integer priority = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
