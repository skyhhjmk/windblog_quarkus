package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

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

    /**
     * 实体标识（支持数字 ID 或字符串 Key）
     */
    @Column(name = "entity_id", nullable = false, length = 255)
    public String entityId;

    /** 操作类型，如 create、update、delete*/
    @Column(nullable = false, length = 50)
    public String action;

    /** 变更前数据（JSONB）*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    public Object oldValue;

    /** 变更后数据（JSONB） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    public Object newValue;

    /** 请求标识，用于串联同一次管理端操作 */
    @Column(name = "request_id", length = 64)
    public String requestId;

    /** 请求方法，例如 GET、POST、PUT、DELETE */
    @Column(name = "request_method", length = 16)
    public String requestMethod;

    /** 请求路径 */
    @Column(name = "request_path", length = 512)
    public String requestPath;

    /** 客户端 IP */
    @Column(name = "client_ip", length = 128)
    public String clientIp;

    /** 用户代理字符串 */
    @Column(name = "user_agent", length = 1024)
    public String userAgent;

    /**
     * 扩展信息，用于存储 AI Token、IP 地址、TraceID 等
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ext_info", columnDefinition = "jsonb")
    public Object extInfo;

    /** 操作人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    public User performedBy;

    /** 操作时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
