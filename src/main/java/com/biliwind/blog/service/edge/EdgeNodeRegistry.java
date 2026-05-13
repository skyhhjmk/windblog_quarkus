package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.scheduler.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EdgeNodeRegistry {
    private static final Logger log = LoggerFactory.getLogger(EdgeNodeRegistry.class);

    private final Map<String, EdgeNodeInfo> nodes = new ConcurrentHashMap<>();

    public void registerOrUpdate(String nodeId, String region, Map<String, String> metrics) {
        EdgeNodeInfo node = nodes.computeIfAbsent(nodeId, id -> {
            log.info("New edge node registered: {} in region {}", id, region);
            return new EdgeNodeInfo(id, region);
        });

        node.setLastHeartbeat(OffsetDateTime.now());
        node.setMetrics(metrics);
        node.setStatus("ONLINE");
    }

    public List<EdgeNodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public EdgeNodeInfo getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public void toggleNodeEnabled(String nodeId, boolean enabled) {
        EdgeNodeInfo node = nodes.get(nodeId);
        if (node != null) {
            node.setEnabled(enabled);
            log.info("Edge node {} {}", nodeId, enabled ? "ENABLED" : "DISABLED");
        }
    }

    @Scheduled(every = "60s")
    void checkNodeHealth() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(90);
        nodes.forEach((id, node) -> {
            if ("ONLINE".equals(node.getStatus()) && node.getLastHeartbeat().isBefore(threshold)) {
                log.warn("Edge node {} timed out, marking as OFFLINE", id);
                node.setStatus("OFFLINE");
            }
        });
    }
}
