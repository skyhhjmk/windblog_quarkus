package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import io.quarkus.grpc.GrpcService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EdgeNodeRegistry {
    private static final Logger log = LoggerFactory.getLogger(EdgeNodeRegistry.class);

    @Inject
    @GrpcService
    EdgeNodeGrpcService edgeNodeGrpcService;

    @Inject
    GrpcChannelFactory channelFactory;

    /**
     * 记录来自边缘节点的心跳或注册请求。
     * 仅接受已通过 mTLS 认证且在数据库中有记录的节点。
     * 如果节点不存在，拒绝注册（节点必须由管理员先在 Flutter 后台创建并签发证书）。
     */
    @Transactional
    public boolean updateHeartbeat(String nodeId, Map<String, String> metrics) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            log.warn("拒绝未知节点的注册请求: {}（节点必须由管理员先创建）", nodeId);
            return false;
        }

        if (!node.isEnabled) {
            log.warn("拒绝已禁用节点的心跳: {}", nodeId);
            return false;
        }

        node.lastHeartbeat = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        node.metrics = metrics;
        node.status = "ONLINE";
        log.debug("节点 {} 心跳更新成功", nodeId);
        return true;
    }

    public List<EdgeNode> getAllNodes() {
        return EdgeNode.listAll();
    }

    public EdgeNode getNode(String nodeId) {
        return EdgeNode.findByNodeId(nodeId);
    }

    @Transactional
    public void toggleNodeEnabled(String nodeId, boolean enabled) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.isEnabled = enabled;
            log.info("Edge node {} {}", nodeId, enabled ? "ENABLED" : "DISABLED");
        }
    }

    @Scheduled(every = "60s")
    @Transactional
    void checkNodeHealth() {
        OffsetDateTime threshold = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(90);
        List<EdgeNode> nodes = EdgeNode.list("status = 'ONLINE'");
        for (EdgeNode node : nodes) {
            if (node.lastHeartbeat != null && node.lastHeartbeat.isBefore(threshold)) {
                log.warn("Edge node {} timed out, marking as OFFLINE", node.nodeId);
                node.status = "OFFLINE";
            }
        }
    }

    /**
     * 轮询主动连接模式的边缘节点。
     * 仅轮询已通过 mTLS 受信任且已启用的节点。
     */
    @Scheduled(every = "30s")
    @Transactional
    public void pollActiveNodes() {
        List<EdgeNode> activeNodes = EdgeNode.list(
                "connectionType = ?1 AND isEnabled = true AND isTrusted = true",
                EdgeConnectionType.ACTIVE_POLL
        );
        if (activeNodes.isEmpty()) {
            return;
        }

        EdgeServiceProto.StorageConfigResponse config = edgeNodeGrpcService.getStorageConfig(
                EdgeServiceProto.ConfigRequest.newBuilder().setNodeId("main").build()
        ).await().atMost(java.time.Duration.ofSeconds(30));

        for (EdgeNode node : activeNodes) {
            String grpcAddress = node.grpcAddress;
            if (grpcAddress == null || grpcAddress.isEmpty()) {
                grpcAddress = node.address;
            }
            if (grpcAddress == null || grpcAddress.isEmpty()) {
                continue;
            }
            log.info("Polling active edge node {} at {}", node.nodeId, grpcAddress);
            try {
                MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = MutinyEdgeNodeServiceGrpc.newMutinyStub(
                        channelFactory.createChannel(grpcAddress)
                );

                EdgeServiceProto.PollRequest request = EdgeServiceProto.PollRequest.newBuilder()
                        .setMainNodeId("main")
                        .setConfig(config)
                        .build();

                stub.poll(request)
                        .subscribe().with(
                                response -> {
                                    updateNodeStatus(response.getNodeId(), response.getMetricsMap());
                                },
                                error -> {
                                    log.error("Failed to poll edge node {}: {}", node.nodeId, error.getMessage());
                                }
                        );
            } catch (Exception e) {
                log.error("Error creating gRPC client for node {}: {}", node.nodeId, e.getMessage());
            }
        }
    }

    @Transactional
    public void updateNodeStatus(String nodeId, Map<String, String> metrics) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.status = "ONLINE";
            node.lastHeartbeat = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            node.metrics = metrics;
        }
    }
}
