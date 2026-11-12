package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mock implements the same primitives as production adapters and is fed by
 * the same prompt builder, so these assertions cover the real JSON path.
 */
class MockAiServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockAiService service = mockService();

    @Test
    void shouldTranslateAllStructuredFields() throws Exception {
        String prompt = TranslationPrompt.build("zh-cn", "en-us", Map.of(
                "title", "标题",
                "summary", "摘要",
                "contentMarkdown", "正文"), objectMapper);

        AiResult result = service.complete(null, AiProviderRequest.json("translate", prompt))
                .toCompletableFuture().join();

        JsonNode translated = objectMapper.readTree(result.contents.get("text"));
        assertEquals(3, translated.size());
        assertEquals("AI Translation (en-us): 标题", translated.get("title").asText());
        assertEquals("AI Translation (en-us): 正文", translated.get("contentMarkdown").asText());
        assertTrue(result.totalTokens > 0, "mock must report usage like a real provider");
    }

    @Test
    void shouldReturnParseableModerationDecision() throws Exception {
        AiResult result = service.complete(null,
                        AiProviderRequest.json("moderate", "内容如下：this is spam"))
                .toCompletableFuture().join();

        JsonNode decision = objectMapper.readTree(result.contents.get("text"));
        assertEquals(false, decision.get("isSafe").asBoolean());
        assertEquals(20, decision.get("score").asInt());
    }

    @Test
    void shouldEmitSameChunkEncodingAsRealAdapters() throws Exception {
        List<String> chunks = service.stream(null,
                        AiProviderRequest.stream("test", "sys", "hello", List.of(), true))
                .collect().asList().await().indefinitely();

        assertEquals(3, chunks.size());
        JsonNode first = objectMapper.readTree(chunks.get(0));
        assertEquals("content", first.path("type").asText());
    }

    private MockAiService mockService() {
        MockAiService mock = new MockAiService();
        mock.objectMapper = objectMapper;
        return mock;
    }
}
