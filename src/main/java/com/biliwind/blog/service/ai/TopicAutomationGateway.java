package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Routes topic-automation commands to the storage backend selected by the
 * topic operation setting: WindBlog's local pool for a normal AI provider, or
 * the existing Codex Creator pool otherwise.
 */
@ApplicationScoped
public class TopicAutomationGateway {

    @Inject
    CodexCreatorHttpClient codexClient;

    @Inject
    LocalAiTopicService localService;

    @Inject
    AiTopicModeService modeService;

    @Inject
    ObjectMapper objectMapper;

    public CompletionStage<JsonNode> command(String command, Object payload, String actorId, String traceId) {
        if (!modeService.localMode()) {
            return codexClient.topicCommand(command, payload, actorId, traceId);
        }
        try {
            JsonNode request = payload == null
                    ? objectMapper.createObjectNode() : objectMapper.valueToTree(payload);
            Object data = localService.dispatch(command, request, actorId, traceId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("success", true);
            response.set("data", objectMapper.valueToTree(data));
            return CompletableFuture.completedFuture(response);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
