package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class AiManager {

    @Inject
    Instance<AiService> providers;

    public CompletionStage<Map<String, String>> summarize(Map<String, String> content) {
        List<AiService> availableProviders = providers.stream()
                .filter(AiService::isAvailable)
                .sorted(Comparator.comparingInt(AiService::getPriority))
                .toList();

        if (availableProviders.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("No AI providers available"));
        }

        return trySummarize(availableProviders, 0, content);
    }

    private CompletionStage<Map<String, String>> trySummarize(List<AiService> providers, int index,
            Map<String, String> content) {
        if (index >= providers.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("All AI providers failed"));
        }

        AiService provider = providers.get(index);
        return provider.summarize(content).handle((result, ex) -> {
            if (ex != null) {
                System.err
                        .println("AI Provider " + provider.getClass().getSimpleName() + " failed: " + ex.getMessage());
                return trySummarize(providers, index + 1, content);
            }
            return CompletableFuture.completedStage(result);
        }).thenCompose(stage -> stage);
    }

    public CompletionStage<Boolean> moderate(String content) {
        List<AiService> availableProviders = providers.stream()
                .filter(AiService::isAvailable)
                .sorted(Comparator.comparingInt(AiService::getPriority))
                .toList();

        if (availableProviders.isEmpty()) {
            return CompletableFuture.failedFuture(new RuntimeException("No AI providers available"));
        }

        return tryModerate(availableProviders, 0, content);
    }

    private CompletionStage<Boolean> tryModerate(List<AiService> providers, int index, String content) {
        if (index >= providers.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("All AI providers failed"));
        }

        AiService provider = providers.get(index);
        return provider.moderate(content).handle((result, ex) -> {
            if (ex != null) {
                System.err.println("AI Provider " + provider.getClass().getSimpleName() + " failed to moderate: "
                        + ex.getMessage());
                return tryModerate(providers, index + 1, content);
            }
            return CompletableFuture.completedStage(result);
        }).thenCompose(stage -> stage);
    }
}
