package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 边缘节点实体
 */
@Entity
@Table(name = "edge_nodes")
public class EdgeNode extends PanacheEntityBase {

    /**
     * 节点ID (唯一标识)
     */
    @Id
    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    /**
     * 节点名称
     */
    @Column(nullable = false, length = 100)
    public String name;

    /**
     * 节点地址 (gRPC 端口，用于 ACTIVE_POLL 模式)
     */
    @Column(length = 255)
    public String address;

    /**
     * 区域配置
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EdgeRegion region;

    /**
     * 连接模式
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false)
    public EdgeConnectionType connectionType = EdgeConnectionType.HEARTBEAT;

    /**
     * 是否启用
     */
    @Column(name = "is_enabled", nullable = false)
    public Boolean isEnabled = true;

    /**
     * 节点状态: ONLINE, OFFLINE
     */
    @Column(nullable = false, length = 20)
    public String status = "OFFLINE";

    /**
     * 最后心跳/轮询时间
     */
    @Column(name = "last_heartbeat")
    public OffsetDateTime lastHeartbeat;

    /**
     * 节点指标数据 (JSONB)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, String> metrics;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    public static EdgeNode findByNodeId(String nodeId) {
        return find("nodeId", nodeId).firstResult();
    }
}
