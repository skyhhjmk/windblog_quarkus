package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockAiServiceTest {

    @Test
    void shouldTranslateAllStructuredFields() {
        MockAiService service = new MockAiService();

        AiResult result = service.translate(null, "zh-cn", "en-us", Map.of(
                "title", "标题",
                "summary", "摘要",
                "contentMarkdown", "正文"))
                .toCompletableFuture()
                .join();

        assertEquals(3, result.contents.size());
        assertEquals("AI Translation (en-us): 标题", result.contents.get("title"));
        assertEquals("AI Translation (en-us): 正文", result.contents.get("contentMarkdown"));
    }
}
