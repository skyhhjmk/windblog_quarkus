package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiPollingAlgorithm;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Node selection for polling groups. Retry and accounting live in {@link AiCallExecutor}. */
@ApplicationScoped
public class AiPollingService {
    private static final Logger log = LoggerFactory.getLogger(AiPollingService.class);
    private final Map<Long, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    @Inject
    AiProviderConfigService configService;
    @Inject
    ObjectMapper objectMapper;

    public List<NodeInfo> extractNodes(AiProviderConfig groupConfig) {
        List<NodeInfo> nodes = new ArrayList<>();
        if (groupConfig == null || groupConfig.config == null || groupConfig.config.isBlank()) {
            return nodes;
        }
        try {
            JsonNode root = objectMapper.readTree(groupConfig.config);
            if (root.has("nodes") && root.get("nodes").isArray()) {
                for (JsonNode n : root.get("nodes")) {
                    long id = n.has("id") ? n.get("id").asLong() : 0;
                    int weight = n.has("weight") ? n.get("weight").asInt(1) : 1;
                    if (id > 0) {
                        nodes.add(new NodeInfo(id, weight));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("无法解析轮询组配置: {}", groupConfig.name, e);
        }
        return nodes;
    }

    public AiPollingAlgorithm extractAlgorithm(AiProviderConfig groupConfig) {
        if (groupConfig == null || groupConfig.config == null || groupConfig.config.isBlank()) {
            return AiPollingAlgorithm.ROUND_ROBIN;
        }
        try {
            JsonNode root = objectMapper.readTree(groupConfig.config);
            if (root.has("algorithm")) {
                return AiPollingAlgorithm.from(root.get("algorithm").asText("ROUND_ROBIN"));
            }
        } catch (Exception e) {
            // Fall through to the default algorithm.
        }
        return AiPollingAlgorithm.ROUND_ROBIN;
    }

    /**
     * Resolves the group's enabled provider nodes, starting with the node the
     * polling algorithm picked. Must run on the calling thread: later retry
     * callbacks have no CDI request or transaction context and cannot query.
     */
    public List<AiProviderConfig> candidates(AiProviderConfig groupConfig) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        if (nodes.isEmpty()) {
            return List.of();
        }
        NodeInfo selected = pickNext(nodes, extractAlgorithm(groupConfig), groupConfig.id);
        List<AiProviderConfig> providers = resolveProviders(nodes);
        int start = startIndex(providers, selected);
        if (start <= 0) {
            return providers;
        }
        List<AiProviderConfig> rotated = new ArrayList<>(providers.size());
        for (int i = 0; i < providers.size(); i++) {
            rotated.add(providers.get((start + i) % providers.size()));
        }
        return rotated;
    }

    private List<AiProviderConfig> resolveProviders(List<NodeInfo> nodes) {
        List<AiProviderConfig> providers = new ArrayList<>(nodes.size());
        for (NodeInfo node : nodes) {
            AiProviderConfig config = configService.getById(node.id()).orElse(null);
            if (config != null && config.enabled && config.type == AiConfigType.PROVIDER) {
                providers.add(config);
            }
        }
        return providers;
    }

    private static int startIndex(List<AiProviderConfig> providers, NodeInfo selected) {
        if (selected == null) {
            return 0;
        }
        for (int i = 0; i < providers.size(); i++) {
            if (selected.id().equals(providers.get(i).id)) {
                return i;
            }
        }
        return 0;
    }

    private NodeInfo pickNext(List<NodeInfo> nodes, AiPollingAlgorithm alg, Long groupId) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);

        if (alg == AiPollingAlgorithm.MULTI_MASTER_MULTI_BACKUP) {
            return nodes.get(0);
        } else if (alg == AiPollingAlgorithm.WEIGHTED_ROUND_ROBIN) {
            int totalWeight = nodes.stream().mapToInt(NodeInfo::weight).sum();
            if (totalWeight <= 0) return nodes.get(0);

            AtomicInteger counter = roundRobinCounters.computeIfAbsent(groupId, k -> new AtomicInteger(0));
            int val = Math.abs(counter.getAndIncrement() % totalWeight);

            int current = 0;
            for (NodeInfo n : nodes) {
                current += n.weight();
                if (val < current) return n;
            }
            return nodes.get(0);
        } else {
            AtomicInteger counter = roundRobinCounters.computeIfAbsent(groupId, k -> new AtomicInteger(0));
            int idx = Math.abs(counter.getAndIncrement() % nodes.size());
            return nodes.get(idx);
        }
    }

    public record NodeInfo(Long id, int weight) {
    }
}
