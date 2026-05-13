package com.biliwind.blog.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "system_settings_history")
public class SystemSettingHistory extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "setting_id", nullable = false)
    public Long settingId;

    @Column(name = "config_key", nullable = false, length = 128)
    public String configKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_value", nullable = false, columnDefinition = "jsonb")
    public JsonNode configValue;

    @Column(nullable = false)
    public Integer version;

    @Column(name = "operator_id")
    public Long operatorId;

    @Column(name = "change_reason", columnDefinition = "TEXT")
    public String changeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
