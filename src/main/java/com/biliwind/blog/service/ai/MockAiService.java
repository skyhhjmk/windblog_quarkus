package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;

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
    public CompletionStage<AiResult> summarize(com.biliwind.blog.model.AiProviderConfig config, Map<String, String> contentByLanguage) {
        AiResult result = new AiResult();
        for (Map.Entry<String, String> entry : contentByLanguage.entrySet()) {
            String lang = entry.getKey();
            String text = entry.getValue();
            String summary = "AI Summary (" + lang + "): "
                    + (text.length() > 50 ? text.substring(0, 50) + "..." : text);
            result.contents.put(lang, summary);
            result.addUsage(10, 20, 30);
        }
        return CompletableFuture.completedStage(result);
    }

    @Override
    public CompletionStage<AiResult> moderate(com.biliwind.blog.model.AiProviderConfig config, String content) {
        // Simple mock moderation: if content contains "spam", it's not safe
        boolean safe = true;
        if (content.toLowerCase().contains("spam")) {
            safe = false;
        }

        AiResult res = new AiResult();
        res.isSafe = safe;
        res.addUsage(5, 5, 10);
        return CompletableFuture.completedStage(res);
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
