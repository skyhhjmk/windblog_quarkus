package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 风险设备封禁，对应 risk_devices。
 */
@Entity
@Table(name = "risk_devices")
public class RiskDevice extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 风险设备 ID
     */
    @Column(name = "device_risk_id", nullable = false, length = 128, unique = true)
    public String deviceRiskId;

    /**
     * 封禁原因
     */
    @Column(columnDefinition = "text")
    public String reason;

    /**
     * 状态：1生效，2停用
     */
    @Column(nullable = false)
    public short status = 1;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
