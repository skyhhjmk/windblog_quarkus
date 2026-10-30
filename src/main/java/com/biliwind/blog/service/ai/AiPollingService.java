package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
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
        if (nodes.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中配置的节点列表为空"));
        }

        NodeInfo selected = pickNext(nodes, alg, groupConfig.id);
        // 节点配置必须在调用线程上一次性解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        List<AiProviderConfig> providers = resolveProviders(nodes);
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中没有可用的节点配置"));
        }

        return tryNodesForSummarize(providers, startIndex(providers, selected), 0, content);
    }

    public CompletionStage<AiResult> translate(AiProviderConfig groupConfig, String sourceLanguage,
            String targetLanguage, Map<String, String> fields, String promptTemplate) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm algorithm = extractAlgorithm(groupConfig);
        if (nodes.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中配置的节点列表为空"));
        }

        NodeInfo selected = pickNext(nodes, algorithm, groupConfig.id);
        // 节点配置必须在调用线程上一次性解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        List<AiProviderConfig> providers = resolveProviders(nodes);
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中没有可用的节点配置"));
        }
        return tryNodesForTranslate(providers, startIndex(providers, selected), 0,
                sourceLanguage, targetLanguage, fields, promptTemplate);
    }

    public CompletionStage<AiResult> complete(AiProviderConfig groupConfig, String systemPrompt, String prompt) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm algorithm = extractAlgorithm(groupConfig);
        if (nodes.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中配置的节点列表为空"));
        }
        NodeInfo selected = pickNext(nodes, algorithm, groupConfig.id);
        // 节点配置必须在调用线程上一次性解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        List<AiProviderConfig> providers = resolveProviders(nodes);
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中没有可用的节点配置"));
        }
        return tryNodesForComplete(providers, startIndex(providers, selected), 0, systemPrompt, prompt);
    }

    public CompletionStage<AiResult> moderate(AiProviderConfig groupConfig, String prompt, String content) {
        List<NodeInfo> nodes = extractNodes(groupConfig);
        AiPollingAlgorithm alg = extractAlgorithm(groupConfig);
        if (nodes.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("轮询组中配置的审核节点列表为空"));
        }

        NodeInfo selected = pickNext(nodes, alg, groupConfig.id);
        // 节点配置必须在调用线程上一次性解析：异步回调线程没有事务/请求上下文，不能访问数据库。
        List<AiProviderConfig> providers = resolveProviders(nodes);
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("轮询组中没有可用的审核节点配置"));
        }

        return tryNodesForModerate(providers, startIndex(providers, selected), 0, prompt, content);
    }

    /**
     * 在当前线程（有事务或请求上下文）按轮询组顺序解析出可用节点。
     * 不能让异步回调线程再查询数据库，否则会抛出
     * "Cannot use the EntityManager/Session because neither a transaction nor a CDI request context is active"。
     */
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

    private CompletionStage<AiResult> tryNodesForSummarize(List<AiProviderConfig> providers, int startIdx,
            int retryCount, Map<String, String> content) {
        if (providers.isEmpty() || retryCount >= providers.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中所有节点调用失败或无节点"));
        }

        AiProviderConfig cfg = providers.get((startIdx + retryCount) % providers.size());
        return aiManager.executeSummarize(cfg, content)
                .handle((res, ex) -> {
                    if (ex != null) {
                        log.warn("轮询节点 {} 失败: {}", cfg.name,
                                SensitiveMessageSanitizer.sanitize(ex.getMessage()));
                        return tryNodesForSummarize(providers, startIdx, retryCount + 1, content);
                    }
                    return CompletableFuture.completedStage(res);
                }).thenCompose(s -> s);
    }

    private CompletionStage<AiResult> tryNodesForTranslate(List<AiProviderConfig> providers, int startIndex,
            int retryCount, String sourceLanguage, String targetLanguage, Map<String, String> fields,
            String promptTemplate) {
        if (providers.isEmpty() || retryCount >= providers.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中所有节点调用翻译失败"));
        }

        AiProviderConfig config = providers.get((startIndex + retryCount) % providers.size());
        return aiManager.executeTranslate(config, sourceLanguage, targetLanguage, fields, promptTemplate)
                .handle((result, error) -> {
                    if (error != null) {
                        log.warn("轮询节点 {} 翻译失败: {}", config.name,
                                SensitiveMessageSanitizer.sanitize(error.getMessage()));
                        return tryNodesForTranslate(providers, startIndex, retryCount + 1,
                                sourceLanguage, targetLanguage, fields, promptTemplate);
                    }
                    return CompletableFuture.completedStage(result);
                }).thenCompose(stage -> stage);
    }

    private CompletionStage<AiResult> tryNodesForComplete(List<AiProviderConfig> providers, int startIndex,
            int retryCount, String systemPrompt, String prompt) {
        if (providers.isEmpty() || retryCount >= providers.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("轮询组中所有节点写作调用失败"));
        }
        AiProviderConfig config = providers.get((startIndex + retryCount) % providers.size());
        return aiManager.executeComplete(config, systemPrompt, prompt)
                .handle((result, error) -> {
                    if (error != null) {
                        log.warn("轮询节点 {} 写作调用失败: {}", config.name,
                                SensitiveMessageSanitizer.sanitize(error.getMessage()));
                        return tryNodesForComplete(providers, startIndex, retryCount + 1, systemPrompt, prompt);
                    }
                    return CompletableFuture.completedStage(result);
                }).thenCompose(stage -> stage);
    }

    private CompletionStage<AiResult> tryNodesForModerate(List<AiProviderConfig> providers, int startIdx,
            int retryCount, String prompt, String content) {
        if (providers.isEmpty() || retryCount >= providers.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("轮询组中所有节点调用审核失败"));
        }

        AiProviderConfig cfg = providers.get((startIdx + retryCount) % providers.size());
        return aiManager.executeModerate(cfg, prompt, content)
                .handle((res, ex) -> {
                    if (ex != null) {
                        log.warn("轮询节点 {} 审核失败: {}", cfg.name,
                                SensitiveMessageSanitizer.sanitize(ex.getMessage()));
                        return tryNodesForModerate(providers, startIdx, retryCount + 1, prompt, content);
                    }
                    return CompletableFuture.completedStage(res);
                }).thenCompose(s -> s);
    }

    // Record for parsed node
    public record NodeInfo(Long id, int weight) {
    }
}
