package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 审计日志实体，对应 audit_logs
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 实体类型，如 post、media */
    @Column(name = "entity_type", nullable = false, length = 50)
    public String entityType;

    /** 实体ID */
    @Column(name = "entity_id", nullable = false)
    public Long entityId;

    /** 操作类型，如 create、update、delete*/
    @Column(nullable = false, length = 50)
    public String action;

    /** 变更前数据（JSONB）*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    public Map<String, Object> oldValue;

    /** 变更后数据（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    public Map<String, Object> newValue;

    /** 操作人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    public User performedBy;

    /** 操作时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
