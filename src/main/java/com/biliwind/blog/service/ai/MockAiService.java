package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class MockAiService implements AiService {

    @Override
    public boolean supports(com.biliwind.blog.model.AiProviderConfig config) {
        return config != null && "MOCK".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<Map<String, String>> summarize(com.biliwind.blog.model.AiProviderConfig config, Map<String, String> contentByLanguage) {
        Map<String, String> summaries = new HashMap<>();
        contentByLanguage.forEach((lang, text) -> {
            String summary = "AI Summary (" + lang + "): "
                    + (text.length() > 50 ? text.substring(0, 50) + "..." : text);
            summaries.put(lang, summary);
        });
        return CompletableFuture.completedStage(summaries);
    }

    @Override
    public CompletionStage<Boolean> moderate(com.biliwind.blog.model.AiProviderConfig config, String content) {
        // Simple mock moderation: if content contains "spam", it's not safe
        boolean safe = !content.toLowerCase().contains("spam");
        return CompletableFuture.completedStage(safe);
    }

    @Override
    public io.smallrye.mutiny.Multi<String> testStream(com.biliwind.blog.model.AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request) {
        return io.smallrye.mutiny.Multi.createFrom().items(
                "{\"type\":\"content\",\"content\":\"This is a mock streaming response.\"}\n",
                "{\"type\":\"content\",\"content\":\" It supports multiple chunks.\"}\n",
                "{\"type\":\"content\",\"content\":\" End of stream.\"}\n"
        );
    }
}
