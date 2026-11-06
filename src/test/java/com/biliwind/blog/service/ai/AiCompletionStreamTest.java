package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.controller.api.admin.AdminAiCompletionController;
import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCompletionStreamTest {

    @Test
    void rejectsEmptyPrompt() {
        AdminAiCompletionController controller = new AdminAiCompletionController();
        assertThrows(BadRequestException.class, () -> {
            controller.completeStream(new AdminAiCompletionController.CompletionRequest(1L, "sys", "   "))
                    .collect().asList().await().indefinitely();
        });
    }

    @Test
    void emitsStartEventImmediatelyFollowedByContentChunks() {
        AiProviderConfig config = new AiProviderConfig();
        config.id = 1L;
        config.name = "mock-provider";
        config.provider = "MOCK";
        config.type = AiConfigType.PROVIDER;
        config.enabled = true;

        AiManager manager = new AiManager() {
            @Override
            public Multi<String> executeCompleteStream(AiProviderConfig cfg, String systemPrompt, String prompt) {
                return Multi.createFrom().items(
                        "{\"type\":\"content\",\"content\":\"Hello \"}",
                        "{\"type\":\"content\",\"content\":\"World!\"}"
                );
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
            public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs, AiProviderConfig current) {
                return null;
            }
        };

        AdminAiCompletionController controller = new AdminAiCompletionController();
        controller.aiManager = manager;

        List<String> events = controller.completeStream(
                new AdminAiCompletionController.CompletionRequest(1L, "sys", "Write a blog post")
        ).collect().asList().await().indefinitely();

        assertEquals(3, events.size());
        assertEquals("{\"type\":\"start\"}", events.get(0));
        assertEquals("{\"type\":\"content\",\"content\":\"Hello \"}", events.get(1));
        assertEquals("{\"type\":\"content\",\"content\":\"World!\"}", events.get(2));

        String joined = AiStreamText.join(events);
        assertEquals("Hello World!", joined);
    }
}
