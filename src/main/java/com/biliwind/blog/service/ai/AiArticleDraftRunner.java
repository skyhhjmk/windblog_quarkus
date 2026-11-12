package com.biliwind.blog.service.ai;

import com.biliwind.blog.service.AiArticleDraftService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Executes AI-provider article drafting outside the HTTP request thread. The
 * run row is committed before scheduling, so a rejected or crashed task can
 * always be failed and surfaced to the admin UI.
 */
@ApplicationScoped
public class AiArticleDraftRunner {

    @Inject
    AiAsyncRunExecutor executor;

    @Inject
    AiArticleDraftService draftService;

    @Inject
    AiArticleDraftRunService runService;

    public void start(Long runId, Long topicId, Long providerId, String language, String instructions,
                      String repostPolicyCode, boolean regenerate, Long operatorId) {
        executor.submit("AI 草稿任务 runId=" + runId, () -> {
            runService.markStarted(runId);
            JsonNode topic = draftService.readTopic(topicId, operatorId);
            AiArticleDraftService.DraftPayload payload =
                    draftService.generateContent(topic, providerId, language, instructions);
            AiArticleDraftService.DraftSaved saved = draftService.persist(topicId, topic, payload,
                    repostPolicyCode, regenerate, operatorId);
            runService.complete(runId, saved.postId(), saved.title(), saved.summary());
        }, error -> runService.fail(runId, AiAsyncRunExecutor.rootMessage(error)));
    }
}
