package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class MockAiService implements AiService {

    @Override
    public CompletionStage<Map<String, String>> summarize(Map<String, String> content) {
        Map<String, String> summaries = new HashMap<>();
        content.forEach((lang, text) -> {
            String summary = "AI Summary (" + lang + "): "
                    + (text.length() > 50 ? text.substring(0, 50) + "..." : text);
            summaries.put(lang, summary);
        });
        return CompletableFuture.completedStage(summaries);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 100; // Low priority for mock
    }

    @Override
    public CompletionStage<Boolean> moderate(String content) {
        // Simple mock moderation: if content contains "spam", it's not safe
        boolean safe = !content.toLowerCase().contains("spam");
        return CompletableFuture.completedStage(safe);
    }
}
