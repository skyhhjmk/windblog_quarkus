package com.biliwind.blog.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "system_settings")
public class SystemSetting extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 128)
    public String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    public JsonNode configValue;

    @Column(name = "config_type", nullable = false, length = 32)
    public String configType = "string";

    @Column(name = "group_name", nullable = false, length = 64)
    public String groupName = "general";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_schema", nullable = false, columnDefinition = "jsonb")
    public JsonNode uiSchema;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(nullable = false)
    public Integer version = 1;

    @Column(name = "is_frozen", nullable = false)
    public boolean isFrozen = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    public static SystemSetting findByKey(String key) {
        return find("configKey", key).firstResult();
    }
}
