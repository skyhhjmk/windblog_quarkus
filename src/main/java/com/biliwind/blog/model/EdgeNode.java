package com.biliwind.blog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 边缘节点实体
 */
@Entity
@Table(name = "edge_nodes")
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdgeNode extends PanacheEntityBase {

    /**
     * 节点ID (唯一标识)
     */
    @Id
    @Column(name = "node_id", nullable = false, length = 100)
    @JsonProperty("nodeId")
    public String nodeId;

    /**
     * 节点名称
     */
    @Column(nullable = false, length = 100)
    @JsonProperty("name")
    public String name;

    /**
     * 外部访问地址 (用户浏览器访问的 URL，如 https://edge.example.com)
     */
    @Column(name = "external_url", length = 255)
    @JsonProperty("externalUrl")
    public String externalUrl;

    /**
     * API 通信地址 (通常与外部访问地址一致，如 http://edge-node:8081)
     */
    @Column(name = "api_url", length = 255)
    @JsonProperty("apiUrl")
    public String apiUrl;

    /**
     * gRPC 通信地址 (主节点向从节点推送数据使用，如 edge-node:9001)
     */
    @Column(name = "grpc_address", length = 255)
    @JsonProperty("grpcAddress")
    public String grpcAddress;

    /**
     * 节点地址 (已废弃，保留向后兼容)
     */
    @Deprecated
    @Column(length = 255)
    public String address;

    /**
     * 区域配置
     */
    @Column(nullable = false)
    @JsonProperty("region")
    public BlogRegion region;

    /**
     * 连接模式
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false)
    @JsonProperty("connectionType")
    public EdgeConnectionType connectionType = EdgeConnectionType.HEARTBEAT;

    /**
     * 是否启用
     */
    @Column(name = "is_enabled", nullable = false)
    @JsonProperty("isEnabled")
    public Boolean isEnabled = true;

    /**
     * 节点状态: ONLINE, OFFLINE
     */
    @Column(nullable = false, length = 20)
    @JsonProperty("status")
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
    @JsonProperty("metrics")
    public Map<String, String> metrics = new HashMap<>();

    /**
     * 证书序列号（唯一）
     */
    @Column(name = "certificate_serial", length = 200, unique = true)
    public String certificateSerial;

    /**
     * 证书过期时间
     */
    @Column(name = "certificate_expiry")
    public OffsetDateTime certificateExpiry;

    /**
     * 证书是否已吊销
     */
    @Column(name = "certificate_revoked", nullable = false)
    @JsonProperty("certificateRevoked")
    public boolean certificateRevoked = false;

    /**
     * 备用证书序列号
     */
    @Column(name = "certificate_backup_serial", length = 200)
    @JsonProperty("certificateBackupSerial")
    public String certificateBackupSerial;

    /**
     * 备用证书过期时间
     */
    @Column(name = "certificate_backup_expiry")
    @JsonProperty("certificateBackupExpiry")
    public OffsetDateTime certificateBackupExpiry;

    /**
     * 是否已受信任（通过 mTLS 首次成功连接）
     */
    @Column(name = "is_trusted", nullable = false)
    @JsonProperty("isTrusted")
    public boolean isTrusted = false;

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
