package com.biliwind.blog.edge;

import com.biliwind.blog.edge.EdgeServiceProto.*;

import io.quarkus.grpc.GrpcClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class EdgeGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(EdgeGrpcClient.class);

    @GrpcClient("main-node")
    EdgeNodeService edgeNodeService;

    @ConfigProperty(name = "edge.node.id", defaultValue = "edge-node-01")
    String nodeId;

    @ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;

    private StorageConfigResponse currentConfig;

    @Scheduled(every = "30s")
    void sendHeartbeat() {
        log.info("Sending heartbeat for node {} in region {}", nodeId, region);
        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setRegion(region)
                .setTimestamp(System.currentTimeMillis())
                .putAllMetrics(collectMetrics())
                .build();

        edgeNodeService.heartbeat(request)
                .subscribe().with(
                        response -> {
                            if (response.getAccepted()) {
                                log.debug("Heartbeat accepted by main node");
                                if (response.hasConfig()) {
                                    this.currentConfig = response.getConfig();
                                    log.debug("Updated storage config version: {}", currentConfig.getConfigVersion());
                                }
                            } else {
                                log.warn("Heartbeat rejected by main node");
                            }
                        },
                        failure -> log.error("Failed to send heartbeat to main node", failure)
                );
    }

    private Map<String, String> collectMetrics() {
        Map<String, String> metrics = new HashMap<>();
        Runtime runtime = Runtime.getRuntime();
        metrics.put("memory.free", String.valueOf(runtime.freeMemory()));
        metrics.put("memory.total", String.valueOf(runtime.totalMemory()));
        metrics.put("memory.max", String.valueOf(runtime.maxMemory()));
        metrics.put("cpu.available", String.valueOf(runtime.availableProcessors()));
        return metrics;
    }

    public StorageConfigResponse getCurrentConfig() {
        return currentConfig;
    }

    public EdgeNodeService getService() {
        return edgeNodeService;
    }
}
