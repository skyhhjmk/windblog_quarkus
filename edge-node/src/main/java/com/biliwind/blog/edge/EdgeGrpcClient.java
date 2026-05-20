package com.biliwind.blog.edge;

import io.quarkus.grpc.GrpcClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class EdgeGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(EdgeGrpcClient.class);

    private final AtomicReference<EdgeServiceProto.StorageConfigResponse> currentConfig = new AtomicReference<>();
    @GrpcClient("main-node")
    MutinyEdgeNodeServiceGrpc.MutinyEdgeNodeServiceStub edgeService;
    @ConfigProperty(name = "edge.node.id")
    String nodeId;
    @ConfigProperty(name = "edge.node.region")
    String region;
    @ConfigProperty(name = "edge.node.connection.type", defaultValue = "HEARTBEAT")
    String connectionType;

    @Inject
    EdgeGrpcPortProvider portProvider;

    @Scheduled(every = "30s")
    void sendHeartbeat() {
        if ("ACTIVE_POLL".equalsIgnoreCase(connectionType)) {
            return;
        }

        log.info("Sending heartbeat for node {} in region {}", nodeId, region);

        int port = portProvider.getGrpcPort();
        log.info("Reporting gRPC port: {}", port);

        EdgeServiceProto.HeartbeatRequest request = EdgeServiceProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setRegion(region)
                .setTimestamp(System.currentTimeMillis())
                .setGrpcPort(port)
                .putAllMetrics(getMetrics())
                .build();

        edgeService.heartbeat(request)
                .subscribe().with(
                        response -> {
                            log.info("Heartbeat acknowledged by main node. Received storage config with {} nodes.",
                                    response.getConfig().getNodesCount());
                            currentConfig.set(response.getConfig());
                        },
                        error -> log.error("Failed to send heartbeat to main node: {}", error.getMessage())
                );
    }

    public void updateConfig(EdgeServiceProto.StorageConfigResponse config) {
        log.info("Updating storage config with {} nodes (version: {})", config.getNodesCount(), config.getConfigVersion());
        currentConfig.set(config);
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getRegion() {
        return region;
    }

    public java.util.Map<String, String> getMetrics() {
        return java.util.Map.of(
                "uptime", String.valueOf(System.currentTimeMillis()),
                "os", System.getProperty("os.name")
        );
    }

    public EdgeServiceProto.StorageConfigResponse getCurrentConfig() {
        return currentConfig.get();
    }
}
