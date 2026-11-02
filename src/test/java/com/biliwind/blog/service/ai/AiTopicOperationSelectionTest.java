package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.ConfigInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTopicOperationSelectionTest {

    @Test
    void configInitializerDefinesTopicOperation() {
        ConfigInitializer initializer = new ConfigInitializer();
        try {
            var field = ConfigInitializer.class.getDeclaredField("mapper");
            field.setAccessible(true);
            field.set(initializer, new ObjectMapper());

            var method = ConfigInitializer.class.getDeclaredMethod("getDefinitions");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<?> definitions = (List<?>) method.invoke(initializer);

            boolean foundTopicOperation = false;
            for (Object def : definitions) {
                var keyField = def.getClass().getDeclaredField("key");
                keyField.setAccessible(true);
                if ("ai_operation_configs".equals(keyField.get(def))) {
                    var schemaField = def.getClass().getDeclaredField("uiSchema");
                    schemaField.setAccessible(true);
                    JsonNode schema = (JsonNode) schemaField.get(def);
                    JsonNode fields = schema.path("fields");
                    for (JsonNode f : fields) {
                        if ("topic".equals(f.path("key").asText())) {
                            foundTopicOperation = true;
                            assertEquals("话题功能 AI 配置", f.path("label").asText());
                        }
                    }
                }
            }
            assertTrue(foundTopicOperation, "ai_operation_configs 必须包含 topic (话题功能) 配置项");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aiManagerSelectsTopicOperationConfigWhenNoProviderIdSpecified() {
        AiProviderConfig topicConfig = provider(10L, "topic-provider");
        AiProviderConfig assistantConfig = provider(20L, "assistant-provider");

        AtomicReference<String> selectedOp = new AtomicReference<>();
        AtomicReference<String> executedProvider = new AtomicReference<>();

        AiManager manager = new AiManager() {
            @Override
            public CompletionStage<AiResult> executeComplete(AiProviderConfig config, String systemPrompt, String prompt) {
                executedProvider.set(config.name);
                AiResult result = new AiResult();
                result.contents = java.util.Map.of("text", "{\"topics\":[]}");
                return CompletableFuture.completedFuture(result);
            }
        };

        manager.configService = new AiProviderConfigService() {
            @Override
            public List<AiProviderConfig> listAll() {
                return List.of(topicConfig, assistantConfig);
            }
        };

        manager.selectionService = new AiSelectionService() {
            @Override
            public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
                selectedOp.set(operation);
                if ("topic".equals(operation)) {
                    return topicConfig;
                }
                return assistantConfig;
            }

            @Override
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs, AiProviderConfig current) {
                return null;
            }
        };

        AiResult res = manager.complete(null, "topic", "sys", "prompt").toCompletableFuture().join();
        assertNotNull(res);
        assertEquals("topic", selectedOp.get(), "应优先使用 topic 操作选择器");
        assertEquals("topic-provider", executedProvider.get(), "应执行 topic 操作对应的提供商/轮询组");
    }

    @Test
    void selectionServicePrefersStandardAiOverCodexCreatorByDefault() {
        AiSelectionService selectionService = new AiSelectionService();
        selectionService.preferCodexCreator = false;
        selectionService.objectMapper = new ObjectMapper();

        AiProviderConfig codexConfig = provider(1L, "codex-creator");
        codexConfig.provider = "CODEX_CREATOR";

        AiProviderConfig standardConfig = provider(2L, "standard-openai");
        standardConfig.provider = "OPENAI";

        // Even if codexConfig is first in the list
        List<AiProviderConfig> configs = List.of(codexConfig, standardConfig);

        AiProviderConfig selected = selectionService.select("topic", configs);
        assertNotNull(selected);
        assertEquals("standard-openai", selected.name, "默认应优先选择普通 AI API 提供商");
    }

    @Test
    void selectionServiceFallsBackToCodexCreatorWhenNoOtherProviderAvailable() {
        AiSelectionService selectionService = new AiSelectionService();
        selectionService.preferCodexCreator = false;
        selectionService.objectMapper = new ObjectMapper();

        AiProviderConfig codexConfig = provider(1L, "codex-creator");
        codexConfig.provider = "CODEX_CREATOR";

        List<AiProviderConfig> configs = List.of(codexConfig);

        AiProviderConfig selected = selectionService.select("topic", configs);
        assertNotNull(selected);
        assertEquals("codex-creator", selected.name, "在无其他普通 AI 提供商时才回退至 Codex Creator");
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
