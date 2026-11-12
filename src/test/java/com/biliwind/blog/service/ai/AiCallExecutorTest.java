package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCallExecutorTest {

    @Test
    void recordsEveryProviderCallIncludingPollingNodes() {
        AiProviderConfig group = provider(99L, "group", "GROUP", AiConfigType.POLLING_GROUP);
        group.config = "{\"nodes\":[{\"id\":11},{\"id\":12}]}";
        AiProviderConfig first = provider(11L, "node-1", "FIRST", AiConfigType.PROVIDER);
        AiProviderConfig second = provider(12L, "node-2", "SECOND", AiConfigType.PROVIDER);

        List<String> recorded = new CopyOnWriteArrayList<>();
        Map<String, AiService> services = new HashMap<>();
        services.put("FIRST", failingService());
        services.put("SECOND", translationService("{\"title\":\"ok\"}"));
        AiCallExecutor executor = executor(List.of(group), services, recorded,
                Map.of(11L, first, 12L, second));

        AiResult result = executor.call(AiCallRequest.translate("en", "zh",
                        Map.of("title", "hello"), "prompt"))
                .toCompletableFuture().join();

        assertEquals("ok", result.contents.get("title"));
        assertEquals(List.of("translate", "translate"), recorded,
                "每个轮询节点都必须记录用量，失败的节点也不例外");
    }

    @Test
    void recordsStreamingCalls() {
        AiProviderConfig config = provider(1L, "stream", "STREAM", AiConfigType.PROVIDER);
        List<String> recorded = new CopyOnWriteArrayList<>();
        AiCallExecutor executor = executor(List.of(config),
                Map.of("STREAM", streamingService()), recorded,
                Map.of(1L, config));

        List<String> chunks = executor.stream(AiCallRequest.completeStream(null, "sys", "prompt"))
                .collect().asList().await().indefinitely();

        assertEquals(2, chunks.size());
        assertEquals(List.of("assistant"), recorded,
                "流式调用同样必须记录用量，即使提供商没有回报 token 数");
    }

    @Test
    void mockRunsThroughTheSameStructuredTranslationPath() {
        AiProviderConfig config = provider(1L, "mock", "MOCK", AiConfigType.PROVIDER);
        MockAiService mock = new MockAiService();
        mock.objectMapper = new ObjectMapper();
        List<String> recorded = new CopyOnWriteArrayList<>();
        AiCallExecutor executor = executor(List.of(config), Map.of("MOCK", mock), recorded,
                Map.of(1L, config));

        AiResult result = executor.call(AiCallRequest.translate("zh-cn", "en-us",
                        Map.of("title", "标题", "contentMarkdown", "正文"), "prompt"))
                .toCompletableFuture().join();

        assertEquals("AI Translation (en-us): 标题", result.contents.get("title"));
        assertEquals("AI Translation (en-us): 正文", result.contents.get("contentMarkdown"));
        assertTrue(result.totalTokens > 0);
        assertEquals(List.of("translate"), recorded);
    }

    private static AiCallExecutor executor(List<AiProviderConfig> configs,
                                           Map<String, AiService> services,
                                           List<String> recorded,
                                           Map<Long, AiProviderConfig> byId) {
        AiCallExecutor executor = new AiCallExecutor() {
            @Override
            AiService findService(AiProviderConfig config) {
                return services.get(config.provider);
            }
        };
        AiProviderConfigService configService = new AiProviderConfigService() {
            @Override
            public List<AiProviderConfig> listAll() {
                return configs;
            }

            @Override
            public Optional<AiProviderConfig> getById(Long id) {
                return Optional.ofNullable(byId.get(id));
            }
        };
        executor.configService = configService;
        AiPollingService polling = new AiPollingService();
        polling.configService = configService;
        polling.objectMapper = new ObjectMapper();
        executor.pollingService = polling;
        executor.selectionService = new AiSelectionService() {
            @Override
            public AiProviderConfig select(String operation, List<AiProviderConfig> all) {
                return all.isEmpty() ? null : all.get(0);
            }

            @Override
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> all,
                                             AiProviderConfig current) {
                return null;
            }
        };
        executor.rateLimiter = new AiRateLimiter();
        executor.concurrencyLimiter = new AiConcurrencyLimiter();
        executor.objectMapper = new ObjectMapper();
        executor.usageService = new AiUsageService() {
            @Override
            public void record(AiProviderConfig config, String feature, AiResult result,
                               long durationMs, Throwable error) {
                recorded.add(feature);
            }
        };
        return executor;
    }

    private static AiService translationService(String json) {
        return new AiService() {
            @Override
            public boolean supports(AiProviderConfig config) {
                return true;
            }

            @Override
            public CompletionStage<AiResult> complete(AiProviderConfig config,
                                                      AiProviderRequest request) {
                AiResult result = new AiResult();
                result.contents.put("text", json);
                result.addUsage(1, 2, 3);
                return CompletableFuture.completedFuture(result);
            }
        };
    }

    private static AiService failingService() {
        return new AiService() {
            @Override
            public boolean supports(AiProviderConfig config) {
                return true;
            }

            @Override
            public CompletionStage<AiResult> complete(AiProviderConfig config,
                                                      AiProviderRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("node unavailable"));
            }
        };
    }

    private static AiService streamingService() {
        return new AiService() {
            @Override
            public boolean supports(AiProviderConfig config) {
                return true;
            }

            @Override
            public CompletionStage<AiResult> complete(AiProviderConfig config,
                                                      AiProviderRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public Multi<String> stream(AiProviderConfig config, AiProviderRequest request) {
                return Multi.createFrom().items("{\"type\":\"content\",\"content\":\"a\"}",
                        "{\"type\":\"content\",\"content\":\"b\"}");
            }
        };
    }

    private static AiProviderConfig provider(Long id, String name, String provider,
                                             AiConfigType type) {
        AiProviderConfig config = new AiProviderConfig();
        config.id = id;
        config.name = name;
        config.provider = provider;
        config.type = type;
        config.enabled = true;
        return config;
    }
}
