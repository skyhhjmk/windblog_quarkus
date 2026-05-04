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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class AiPollingService {
    private static final Logger log = LoggerFactory.getLogger(AiPollingService.class);
    private final Map<Long, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    @Inject
    AiProviderConfigService configService;
    @Inject
    AiManager aiManager;
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
        }
        return AiPollingAlgorithm.ROUND_ROBIN;
    }

    public CompletionStage<AiResult> summarize(AiProviderConfig groupConfig, Map<String, String> content) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm alg = extractAlgorithm(groupConfig);
        return tryNodesForSummarize(nodes, alg, groupConfig.id, 0, content);
    }

    public CompletionStage<AiResult> moderate(AiProviderConfig groupConfig, String prompt, String content) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm alg = extractAlgorithm(groupConfig);
        return tryNodesForModerate(nodes, alg, groupConfig.id, 0, prompt, content);
    }

    public io.smallrye.mutiny.Multi<String> testStream(AiProviderConfig groupConfig, com.biliwind.blog.controller.api.admin.dto.AiTestRequest req) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm alg = extractAlgorithm(groupConfig);
        if (nodes.isEmpty()) {
            return io.smallrye.mutiny.Multi.createFrom().failure(new RuntimeException("轮询组无可用节点"));
        }
        NodeInfo selected = pickNext(nodes, alg, groupConfig.id);
        AiProviderConfig targetConfig = configService.getById(selected.id()).orElse(null);
        if (targetConfig == null || !targetConfig.enabled || targetConfig.type != AiConfigType.PROVIDER) {
            return io.smallrye.mutiny.Multi.createFrom().failure(new RuntimeException("轮询组选定节点失效"));
        }
        return aiManager.testStream(targetConfig, req);
    }

    private NodeInfo pickNext(List<NodeInfo> nodes, AiPollingAlgorithm alg, Long groupId) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);

        if (alg == AiPollingAlgorithm.MULTI_MASTER_MULTI_BACKUP) {
            // First is master, fallback is backup. We always try master first.
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
            // ROUND_ROBIN
            AtomicInteger counter = roundRobinCounters.computeIfAbsent(groupId, k -> new AtomicInteger(0));
            int idx = Math.abs(counter.getAndIncrement() % nodes.size());
            return nodes.get(idx);
        }
    }

    private CompletionStage<AiResult> tryNodesForSummarize(List<NodeInfo> nodes, AiPollingAlgorithm alg, Long groupId, int retryCount, Map<String, String> content) {
        if (nodes.isEmpty() || retryCount >= nodes.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中所有节点调用失败或无节点"));
        }

        NodeInfo selected = pickNext(nodes, alg, groupId);
        AiProviderConfig cfg = configService.getById(selected.id()).orElse(null);
        if (cfg == null || !cfg.enabled || cfg.type != AiConfigType.PROVIDER) {
            return tryNodesForSummarize(nodes, alg, groupId, retryCount + 1, content);
        }

        return aiManager.executeSummarize(cfg, content)
                .handle((res, ex) -> {
                    if (ex != null) {
                        log.warn("轮询节点 {} 失败: {}", cfg.name, ex.getMessage());
                        return tryNodesForSummarize(nodes, alg, groupId, retryCount + 1, content);
                    }
                    return CompletableFuture.completedStage(res);
                }).thenCompose(s -> s);
    }

    private CompletionStage<AiResult> tryNodesForModerate(List<NodeInfo> nodes, AiPollingAlgorithm alg, Long groupId, int retryCount, String prompt, String content) {
        if (nodes.isEmpty() || retryCount >= nodes.size()) {
            AiResult defaultResult = new AiResult();
            defaultResult.isSafe = true;
            return CompletableFuture.completedFuture(defaultResult); // 默认放行
        }

        NodeInfo selected = pickNext(nodes, alg, groupId);
        AiProviderConfig cfg = configService.getById(selected.id()).orElse(null);
        if (cfg == null || !cfg.enabled || cfg.type != AiConfigType.PROVIDER) {
            return tryNodesForModerate(nodes, alg, groupId, retryCount + 1, prompt, content);
        }

        return aiManager.executeModerate(cfg, prompt, content)
                .handle((res, ex) -> {
                    if (ex != null) {
                        log.warn("轮询节点 {} 审核失败: {}", cfg.name, ex.getMessage());
                        return tryNodesForModerate(nodes, alg, groupId, retryCount + 1, prompt, content);
                    }
                    return CompletableFuture.completedStage(res);
                }).thenCompose(s -> s);
    }

    // Record for parsed node
    public record NodeInfo(Long id, int weight) {
    }
}
