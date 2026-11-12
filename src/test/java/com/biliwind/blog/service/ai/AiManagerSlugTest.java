package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiManagerSlugTest {

    @Test
    void shouldReturnEmptyForBlankOrNullTitle() {
        AiManager manager = managerWith(request -> {
            throw new AssertionError("blank titles must not call the provider");
        });

        assertEquals("", manager.generateSlug(null).toCompletableFuture().join());
        assertEquals("", manager.generateSlug("   ").toCompletableFuture().join());
    }

    @Test
    void shouldSlugifyAsciiTitleDirectlyWithoutAi() {
        AiManager manager = managerWith(request -> {
            throw new AssertionError("ASCII titles must not call the provider");
        });

        assertEquals("hello-world", manager.generateSlug("Hello World!").toCompletableFuture().join());
        assertEquals("quarkus-native-guide",
                manager.generateSlug("Quarkus Native Guide").toCompletableFuture().join());
    }

    @Test
    void shouldTranslateChineseTitleToEnglishSlugWhenAiSucceeds() {
        AiManager manager = managerWith(request -> {
            AiResult result = new AiResult();
            result.contents.put("text", "how-to-build-native-applications-with-quarkus");
            return CompletableFuture.completedFuture(result);
        });

        assertEquals("how-to-build-native-applications-with-quarkus",
                manager.generateSlug("如何用 Quarkus 构建原生应用").toCompletableFuture().join());
    }

    @Test
    void shouldFallbackToPinyinWhenAiFails() {
        AiManager manager = managerWith(request ->
                CompletableFuture.failedFuture(new RuntimeException("API quota exceeded")));

        assertEquals("ru-he-yong-quarkus-gou-jian-yuan-sheng-ying-yong",
                manager.generateSlug("如何用 Quarkus 构建原生应用").toCompletableFuture().join());
    }

    @Test
    void shouldFallbackToPinyinWhenNoAiConfigured() {
        AiCallExecutor executor = new AiCallExecutor() {
            @Override
            public CompletionStage<AiResult> call(AiCallRequest request) {
                return CompletableFuture.failedFuture(new RuntimeException("没有可用的 AI 配置"));
            }
        };
        AiManager manager = new AiManager();
        manager.executor = executor;

        assertEquals("kuai-su-ru-men-zhi-nan",
                manager.generateSlug("快速入门指南").toCompletableFuture().join());
    }

    private static AiManager managerWith(Function<AiCallRequest, CompletionStage<AiResult>> call) {
        AiManager manager = new AiManager();
        manager.executor = new AiCallExecutor() {
            @Override
            public CompletionStage<AiResult> call(AiCallRequest request) {
                return call.apply(request);
            }
        };
        return manager;
    }
}
