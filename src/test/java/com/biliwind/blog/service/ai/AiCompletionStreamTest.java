package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.controller.api.admin.AdminAiCompletionController;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        AiManager manager = new AiManager();
        manager.executor = new AiCallExecutor() {
            @Override
            public Multi<String> stream(AiCallRequest request) {
                return Multi.createFrom().items(
                        "{\"type\":\"content\",\"content\":\"Hello \"}",
                        "{\"type\":\"content\",\"content\":\"World!\"}"
                );
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
