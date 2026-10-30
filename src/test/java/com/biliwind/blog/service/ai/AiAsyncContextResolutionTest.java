package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 调用刻意脱离事务执行，因此所有降级/轮询节点的数据库解析都必须发生在
 * 调用线程上。一旦放进 {@code exceptionallyCompose} / {@code handle} 回调，
 * 回调线程既没有事务也没有 CDI 请求上下文，Panache 会抛出
 * "Cannot use the EntityManager/Session because neither a transaction nor a CDI
 * request context is active"，批量翻译任务就会整体失败。
 */
class AiAsyncContextResolutionTest {

    @Test
    void resolvesFallbackOnCallingThread() {
        AiProviderConfig primary = provider(1L, "primary");
        AiProviderConfig fallback = provider(2L, "fallback");
        AtomicReference<Thread> fallbackThread = new AtomicReference<>();
        AtomicReference<String> usedConfig = new AtomicReference<>();

        ExecutorService aiThread = Executors.newSingleThreadExecutor();
        try {
            AiManager manager = new AiManager() {
                @Override
                public CompletionStage<AiResult> executeTranslate(AiProviderConfig config, String sourceLanguage,
                        String targetLanguage, Map<String, String> fields, String promptTemplate) {
                    usedConfig.set(config.name);
                    return afterDelay(aiThread, () -> {
                        if (config == primary) {
                            throw new IllegalStateException("primary unavailable");
                        }
                        return new AiResult();
                    });
                }
            };
            manager.configService = new AiProviderConfigService() {
                @Override
                public List<AiProviderConfig> listAll() {
                    return List.of(primary, fallback);
                }
            };
            manager.selectionService = new AiSelectionService() {
                @Override
                public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
                    return primary;
                }

                @Override
                public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs,
                                                 AiProviderConfig current) {
                    fallbackThread.set(Thread.currentThread());
                    return fallback;
                }
            };

            Thread caller = Thread.currentThread();
            AiResult result = manager.translate("en", "zh", Map.of("title", "hello"), "prompt")
                    .toCompletableFuture().join();

            assertNotNull(result);
            assertSame(caller, fallbackThread.get(),
                    "降级配置必须在调用线程上解析，异步回调线程没有 CDI/事务上下文");
            assertEquals("fallback", usedConfig.get());
        } finally {
            aiThread.shutdownNow();
        }
    }

    @Test
    void resolvesPollingNodesBeforeAsyncRetries() {
        AiProviderConfig first = provider(11L, "node-1");
        AiProviderConfig second = provider(12L, "node-2");
        AiProviderConfig group = provider(99L, "group");
        group.type = AiConfigType.POLLING_GROUP;
        group.config = "{\"nodes\":[{\"id\":11},{\"id\":12}]}";

        Thread caller = Thread.currentThread();
        List<Thread> lookupThreads = new CopyOnWriteArrayList<>();
        ExecutorService aiThread = Executors.newSingleThreadExecutor();
        try {
            AiPollingService polling = new AiPollingService();
            polling.objectMapper = new ObjectMapper();
            polling.configService = new AiProviderConfigService() {
                @Override
                public Optional<AiProviderConfig> getById(Long id) {
                    lookupThreads.add(Thread.currentThread());
                    if (id != null && id == 11L) {
                        return Optional.of(first);
                    }
                    if (id != null && id == 12L) {
                        return Optional.of(second);
                    }
                    return Optional.empty();
                }
            };
            polling.aiManager = new AiManager() {
                @Override
                public CompletionStage<AiResult> executeTranslate(AiProviderConfig config, String sourceLanguage,
                        String targetLanguage, Map<String, String> fields, String promptTemplate) {
                    return afterDelay(aiThread, () -> {
                        if (config == first) {
                            throw new IllegalStateException("node unavailable");
                        }
                        return new AiResult();
                    });
                }
            };

            AiResult result = polling.translate(group, "en", "zh", Map.of("title", "hello"), "prompt")
                    .toCompletableFuture().join();

            assertNotNull(result);
            assertEquals(2, lookupThreads.size());
            assertTrue(lookupThreads.stream().allMatch(thread -> thread == caller),
                    "轮询节点配置必须在调用线程上解析，重试回调线程没有 CDI/事务上下文");
        } finally {
            aiThread.shutdownNow();
        }
    }

    /**
     * 延迟完成异步结果，确保后续回调（handle/exceptionallyCompose）真的在
     * AI 线程上执行，而不是因为结果已就绪而内联回调用线程。
     */
    private static <T> CompletionStage<T> afterDelay(ExecutorService executor,
                                                     java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return supplier.get();
        }, executor);
    }

    private static AiProviderConfig provider(Long id, String name) {
        AiProviderConfig config = new AiProviderConfig();
        config.id = id;
        config.name = name;
        config.provider = "OPENAI";
        config.enabled = true;
        config.type = AiConfigType.PROVIDER;
        return config;
    }
}
