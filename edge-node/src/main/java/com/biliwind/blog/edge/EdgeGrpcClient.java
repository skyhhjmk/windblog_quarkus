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

    @Scheduled(every = "30s")
    void sendHeartbeat() {
        log.info("Sending heartbeat for node {} in region {}", nodeId, region);

        EdgeServiceProto.HeartbeatRequest request = EdgeServiceProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setRegion(region)
                .setTimestamp(System.currentTimeMillis())
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

    public EdgeServiceProto.StorageConfigResponse getCurrentConfig() {
        return currentConfig.get();
    }
}
