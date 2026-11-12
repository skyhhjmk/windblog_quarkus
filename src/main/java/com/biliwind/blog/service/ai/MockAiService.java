package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Deterministic provider used by tests and local installs. It implements the
 * same two primitives as every real adapter and is invoked by the same
 * {@link AiCallExecutor} path, including prompt construction, JSON parsing and
 * usage accounting.
 */
@ApplicationScoped
public class MockAiService implements AiService {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean supports(com.biliwind.blog.model.AiProviderConfig config) {
        return config != null && "MOCK".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> complete(com.biliwind.blog.model.AiProviderConfig config,
                                              AiProviderRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        String text;
        if (request.jsonResponse()) {
            text = "moderate".equals(request.operation())
                    ? moderationJson(prompt) : translationJson(prompt);
        } else if (prompt.startsWith("Title: ")) {
            text = "mock-slug-english-result";
        } else {
            text = "This is a mock complete response.";
        }
        AiResult result = new AiResult();
        result.contents.put("text", text);
        result.rawResponse = text;
        int promptTokens = Math.max(1, prompt.length() / 4);
        int completionTokens = Math.max(1, text.length() / 4);
        result.addUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        return CompletableFuture.completedStage(result);
    }

    @Override
    public Multi<String> stream(com.biliwind.blog.model.AiProviderConfig config,
                                AiProviderRequest request) {
        if (!request.stream()) {
            return Multi.createFrom().item(chunk("content", "This is a mock streaming response."));
        }
        return Multi.createFrom().items(
                chunk("content", "This is a mock streaming response."),
                chunk("content", " It supports multiple chunks."),
                chunk("content", " End of stream."));
    }

    private String chunk(String type, String content) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", type, "content", content));
        } catch (Exception exception) {
            throw new IllegalStateException("Mock AI 响应编码失败", exception);
        }
    }

    private String moderationJson(String prompt) {
        boolean safe = !prompt.toLowerCase(java.util.Locale.ROOT).contains("spam");
        ObjectNode decision = objectMapper.createObjectNode();
        decision.put("isSafe", safe);
        decision.put("score", safe ? 95 : 20);
        decision.put("reason", safe ? "Mock 判定内容安全" : "Mock 判定内容存在风险");
        return decision.toString();
    }

    private String translationJson(String prompt) {
        int start = prompt.lastIndexOf("{\"sourceLanguage\"");
        if (start < 0) {
            return "{}";
        }
        try {
            JsonNode input = objectMapper.readTree(prompt.substring(start));
            JsonNode fields = input.path("fields");
            String target = input.path("targetLanguage").asText("");
            ObjectNode output = objectMapper.createObjectNode();
            fields.fieldNames().forEachRemaining(field ->
                    output.put(field, "AI Translation (" + target + "): "
                            + fields.path(field).asText("")));
            return output.toString();
        } catch (Exception exception) {
            return "{}";
        }
    }
}
