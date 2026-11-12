package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    AiAsyncRunExecutor executor;

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
        executor.submit("话题发现 runId=" + runId, () -> {
            runService.markStarted(runId);
            List<Map<String, Object>> topics = discoveryService.discover(
                    providerId, seeds, maxTopics, language);
            localTopicService.dispatch("topics.import",
                    objectMapper.valueToTree(Map.of("topics", topics)), actorId, traceId);
            runService.complete(runId, topics.size());
        }, error -> runService.fail(runId, AiAsyncRunExecutor.rootMessage(error)));
    }
}
