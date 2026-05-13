package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto;
import com.biliwind.blog.edge.MutinyEdgeNodeServiceGrpc;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.biliwind.blog.model.EdgeRegion;
import io.grpc.ManagedChannelBuilder;
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

    @Transactional
    public void registerOrUpdate(String nodeId, String regionStr, Map<String, String> metrics) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            log.info("New edge node registered: {} in region {}", nodeId, regionStr);
            node = new EdgeNode();
            node.nodeId = nodeId;
            node.name = nodeId; // 默认名称
            try {
                node.region = EdgeRegion.valueOf(regionStr.toUpperCase());
            } catch (Exception e) {
                node.region = EdgeRegion.GLOBAL;
            }
            node.connectionType = EdgeConnectionType.HEARTBEAT;
            node.persist();
        }

        node.lastHeartbeat = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        node.metrics = metrics;
        node.status = "ONLINE";
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

    @Scheduled(every = "30s")
    @Transactional
    public void pollActiveNodes() {
        List<EdgeNode> activeNodes = EdgeNode.list("connectionType = ?1 AND isEnabled = true", EdgeConnectionType.ACTIVE_POLL);
        if (activeNodes.isEmpty()) {
            return;
        }

        // 获取当前存储配置
        EdgeServiceProto.StorageConfigResponse config = edgeNodeGrpcService.getStorageConfig(
                EdgeServiceProto.ConfigRequest.newBuilder().setNodeId("main").build()
        ).await().indefinitely();

        for (EdgeNode node : activeNodes) {
            if (node.address == null || node.address.isEmpty()) {
                continue;
            }
            log.info("Polling active edge node {} at {}", node.nodeId, node.address);
            try {
                // 动态创建 gRPC 客户端连接从节点
                MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = MutinyEdgeNodeServiceGrpc.newMutinyStub(
                        ManagedChannelBuilder.forTarget(node.address).usePlaintext().build()
                );

                EdgeServiceProto.PollRequest request = EdgeServiceProto.PollRequest.newBuilder()
                        .setMainNodeId("main")
                        .setConfig(config)
                        .build();

                stub.poll(request)
                        .subscribe().with(
                                response -> {
                                    updateNodeStatus(response.getNodeId(), response.getRegion(), response.getMetricsMap());
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
    public void updateNodeStatus(String nodeId, String region, Map<String, String> metrics) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.status = "ONLINE";
            node.lastHeartbeat = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            node.metrics = metrics;
        }
    }
}
