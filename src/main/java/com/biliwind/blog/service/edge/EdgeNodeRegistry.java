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

    /**
     * 线程安全的通道连接缓存，按 nodeId 复用连接以彻底杜绝句柄和 Socket 泄露
     */
    private final java.util.Map<String, ChannelEntry> channelCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    GrpcChannelFactory channelFactory;
    @Inject
    @GrpcService // 必须带上此限定符，因为 EdgeNodeGrpcService 在 CDI 容器中仅带有 @GrpcService 限定符。由于注入类型是具体的实现类而非接口或 Stub，这依然是纯本地方法调用，不会产生 gRPC 网络请求。
    EdgeNodeGrpcService edgeNodeGrpcService;

    @Transactional
    public void toggleNodeEnabled(String nodeId, boolean enabled) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node != null) {
            node.isEnabled = enabled;
            log.info("Edge node {} {}", nodeId, enabled ? "ENABLED" : "DISABLED");
            if (!enabled) {
                node.status = "OFFLINE";
                // 禁用时清理并关闭缓存中的连接通道
                ChannelEntry entry = channelCache.remove(nodeId);
                if (entry != null) {
                    try {
                        entry.channel.shutdownNow();
                    } catch (Exception e) {
                        log.error("Failed to shutdown channel for disabled node {}: {}", nodeId, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 记录来自边缘节点的心跳或注册请求。
     * 仅接受已通过 mTLS 认证且在数据库中有记录的节点。
     * 如果节点不存在，拒绝注册（节点必须由管理员先在 Flutter 后台创建并签发证书）。
     */
    @Transactional
    public boolean updateHeartbeat(String nodeId, Map<String, String> metrics, int grpcPort) {
        EdgeNode node = EdgeNode.findByNodeId(nodeId);
        if (node == null) {
            log.warn("拒绝未知节点的注册请求: {}（节点必须由管理员先创建）", nodeId);
            return false;
        }

        if (!node.isEnabled) {
            log.warn("拒绝已禁用节点的心跳: {}", nodeId);
            return false;
        }

        if (grpcPort > 0) {
            updateNodeGrpcAddress(node, grpcPort);
        }

        node.lastHeartbeat = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        node.metrics = metrics;
        node.status = "ONLINE";
        log.debug("节点 {} 心跳更新成功", nodeId);
        return true;
    }

    private void updateNodeGrpcAddress(EdgeNode node, int grpcPort) {
        String host = extractHostFromAddress(node.grpcAddress);
        if (host == null) {
            host = extractHostFromAddress(node.address);
        }
        if (host == null) {
            log.warn("无法从现有地址提取主机名，跳过节点 {} 的 gRPC 端口更新", node.nodeId);
            return;
        }

        String newAddress = host + ":" + grpcPort;
        if (!newAddress.equals(node.grpcAddress)) {
            node.grpcAddress = newAddress;
            log.info("Edge node {} gRPC port updated: {}", node.nodeId, newAddress);
        }
    }

    private String extractHostFromAddress(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }

        int lastColon = address.lastIndexOf(':');
        if (lastColon < 0) {
            return address;
        }

        String host = address.substring(0, lastColon);
        if (host.isEmpty()) {
            return null;
        }

        return host;
    }

    public List<EdgeNode> getAllNodes() {
        return EdgeNode.listAll();
    }

    public EdgeNode getNode(String nodeId) {
        return EdgeNode.findByNodeId(nodeId);
    }

    /**
     * 轮询主动连接模式的边缘节点。
     * 仅轮询已通过 mTLS 受信任且已启用的节点。
     */
    @Scheduled(every = "30s")
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
                ChannelEntry entry = channelCache.get(node.nodeId);

                // 如果缓存为空，或者连接目标的物理地址（IP 端口）发生改变，则安全销毁旧连接并重建
                if (entry == null || !entry.grpcAddress.equals(grpcAddress)) {
                    if (entry != null) {
                        try {
                            log.info("Closing legacy channel for node {} due to address change from {} to {}",
                                    node.nodeId, entry.grpcAddress, grpcAddress);
                            entry.channel.shutdownNow();
                        } catch (Exception ex) {
                            log.error("Error shutting down legacy channel for node {}: {}", node.nodeId, ex.getMessage());
                        }
                    }
                    io.grpc.ManagedChannel newChannel = channelFactory.createChannel(grpcAddress, node.nodeId);
                    entry = new ChannelEntry(grpcAddress, newChannel);
                    channelCache.put(node.nodeId, entry);
                }

                MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub stub = MutinyEdgeNodeServiceGrpc.newMutinyStub(entry.channel);

                EdgeServiceProto.PollRequest request = EdgeServiceProto.PollRequest.newBuilder()
                        .setMainNodeId("main")
                        .setConfig(config)
                        .build();

                String pollingGrpcAddress = grpcAddress;
                stub.poll(request)
                        .subscribe().with(
                                response -> {
                                    updateNodeStatus(response.getNodeId(), response.getMetricsMap());
                                },
                                error -> {
                                    log.error("Failed to poll edge node {} at {}", node.nodeId, pollingGrpcAddress, error);
                                }
                        );
            } catch (Exception e) {
                log.error("Error creating gRPC client for node {} at {}", node.nodeId, grpcAddress, e);
            }
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
     * 优雅停止：在 JVM / Quarkus 停止时，主动且安全地销毁所有活跃连接
     */
    public void onStop(@jakarta.enterprise.event.Observes io.quarkus.runtime.ShutdownEvent event) {
        log.info("Shutting down active gRPC channels in EdgeNodeRegistry...");
        for (ChannelEntry entry : channelCache.values()) {
            try {
                entry.channel.shutdownNow();
            } catch (Exception e) {
                log.error("Failed to shutdown channel during application shutdown: {}", e.getMessage());
            }
        }
        channelCache.clear();
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

    /**
     * 用于封装已建立的 gRPC 连接条目，支持 IP 变更对比
     */
    private static class ChannelEntry {
        final String grpcAddress;
        final io.grpc.ManagedChannel channel;

        ChannelEntry(String grpcAddress, io.grpc.ManagedChannel channel) {
            this.grpcAddress = grpcAddress;
            this.channel = channel;
        }
    }
}
