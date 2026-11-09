package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiManagerSlugTest {

    @Test
    void shouldReturnEmptyForBlankOrNullTitle() {
        AiManager manager = new AiManager();

        assertEquals("", manager.generateSlug(null).toCompletableFuture().join());
        assertEquals("", manager.generateSlug("   ").toCompletableFuture().join());
    }

    @Test
    void shouldSlugifyAsciiTitleDirectlyWithoutAi() {
        AiManager manager = new AiManager();

        assertEquals("hello-world", manager.generateSlug("Hello World!").toCompletableFuture().join());
        assertEquals("quarkus-native-guide", manager.generateSlug("Quarkus Native Guide").toCompletableFuture().join());
    }

    @Test
    void shouldTranslateChineseTitleToEnglishSlugWhenAiSucceeds() {
        AiProviderConfig config = new AiProviderConfig();
        config.id = 1L;
        config.name = "mock-ai";
        config.enabled = true;
        config.type = AiConfigType.PROVIDER;

        AiManager manager = new AiManager() {
            @Override
            public CompletionStage<AiResult> executeComplete(AiProviderConfig cfg, String systemPrompt, String prompt) {
                AiResult result = new AiResult();
                result.contents.put("text", "how-to-build-native-applications-with-quarkus");
                return CompletableFuture.completedFuture(result);
            }
        };

        manager.configService = new AiProviderConfigService() {
            @Override
            public List<AiProviderConfig> listAll() {
                return List.of(config);
            }
        };
        manager.selectionService = new AiSelectionService() {
            @Override
            public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
                return config;
            }

            @Override
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs, AiProviderConfig primary) {
                return null;
            }
        };

        String slug = manager.generateSlug("如何用 Quarkus 构建原生应用").toCompletableFuture().join();
        assertEquals("how-to-build-native-applications-with-quarkus", slug);
    }

    @Test
    void shouldFallbackToPinyinWhenAiFails() {
        AiProviderConfig config = new AiProviderConfig();
        config.id = 1L;
        config.name = "failing-ai";
        config.enabled = true;
        config.type = AiConfigType.PROVIDER;

        AiManager manager = new AiManager() {
            @Override
            public CompletionStage<AiResult> executeComplete(AiProviderConfig cfg, String systemPrompt, String prompt) {
                return CompletableFuture.failedFuture(new RuntimeException("API quota exceeded"));
            }
        };

        manager.configService = new AiProviderConfigService() {
            @Override
            public List<AiProviderConfig> listAll() {
                return List.of(config);
            }
        };
        manager.selectionService = new AiSelectionService() {
            @Override
            public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
                return config;
            }

            @Override
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs, AiProviderConfig primary) {
                return null;
            }
        };

        String slug = manager.generateSlug("如何用 Quarkus 构建原生应用").toCompletableFuture().join();
        assertEquals("ru-he-yong-quarkus-gou-jian-yuan-sheng-ying-yong", slug);
    }

    @Test
    void shouldFallbackToPinyinWhenNoAiConfigured() {
        AiManager manager = new AiManager();
        manager.configService = new AiProviderConfigService() {
            @Override
            public List<AiProviderConfig> listAll() {
                return List.of();
            }
        };
        manager.selectionService = new AiSelectionService();

        String slug = manager.generateSlug("快速入门指南").toCompletableFuture().join();
        assertEquals("kuai-su-ru-men-zhi-nan", slug);
    }
}
