package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.List;
import java.util.Map;

/**
 * Runs provider topic discovery off the HTTP request thread. The admin request
 * only creates a run, so a slow AI provider can no longer trip the public edge
 * timeout (HTTP 554); the UI polls the run until it succeeds or fails.
 */
@ApplicationScoped
public class AiTopicRunner {

    @Inject
    ManagedExecutor executor;

    @Inject
    AiTopicDiscoveryService discoveryService;

    @Inject
    LocalAiTopicService localTopicService;

    @Inject
    AiTopicRunService runService;

    @Inject
    ObjectMapper objectMapper;

    public void start(Long runId, Long providerId, List<AiTopicDiscoveryService.Seed> seeds,
                      int maxTopics, String language, String actorId, String traceId) {
        executor.runAsync(() -> execute(runId, providerId, seeds, maxTopics, language, actorId, traceId))
                .exceptionally(error -> {
                    runService.fail(runId, rootMessage(error));
                    return null;
                });
    }

    private void execute(Long runId, Long providerId, List<AiTopicDiscoveryService.Seed> seeds,
                         int maxTopics, String language, String actorId, String traceId) {
        runService.markStarted(runId);
        try {
            List<Map<String, Object>> topics = discoveryService.discover(
                    providerId, seeds, maxTopics, language);
            localTopicService.dispatch("topics.import",
                    objectMapper.valueToTree(Map.of("topics", topics)), actorId, traceId);
            runService.complete(runId, topics.size());
        } catch (RuntimeException exception) {
            runService.fail(runId, rootMessage(exception));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
