package com.biliwind.blog.service.ai;

import com.biliwind.blog.controller.api.admin.dto.AiTestRequest;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class CodexCreatorAiService implements AiService {
    @Inject
    CodexCreatorHttpClient client;

    @Override
    public boolean supports(AiProviderConfig config) {
        return config != null && "CODEX_CREATOR".equalsIgnoreCase(config.provider);
    }

    @Override
    public CompletionStage<AiResult> summarize(AiProviderConfig config, Map<String, String> content) {
        return callDetailed(config, "summarize", content).thenApply(this::contentResultWithProvenance);
    }

    @Override
    public CompletionStage<AiResult> translate(AiProviderConfig config, String sourceLanguage,
                                               String targetLanguage, Map<String, String> fields) {
        return callDetailed(config, "translate", Map.of("sourceLanguage", sourceLanguage,
                "targetLanguage", targetLanguage, "fields", fields)).thenApply(this::contentResultWithProvenance);
    }

    @Override
    public CompletionStage<AiResult> moderate(AiProviderConfig config, String prompt, String content) {
        return callDetailed(config, "moderate", Map.of("prompt", prompt, "content", content))
                .thenApply(this::moderationResultWithProvenance);
    }

    @Override
    public Multi<String> testStream(AiProviderConfig config, AiTestRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", request.prompt());
        if (request.systemPrompt() != null) input.put("systemPrompt", request.systemPrompt());
        return Multi.createFrom().completionStage(call(config, "assistant", input))
                .map(JsonNode::toString);
    }

    private CompletionStage<JsonNode> call(AiProviderConfig config, String operation, Object input) {
        String profileId = config.model == null || config.model.isBlank() ? "codex-default" : config.model;
        String key = "windblog:" + operation + ":" + java.util.UUID.randomUUID();
        return client.infer(config, operation, profileId, input, key, java.util.UUID.randomUUID().toString());
    }

    private CompletionStage<CodexCreatorInference> callDetailed(AiProviderConfig config, String operation, Object input) {
        String profileId = config.model == null || config.model.isBlank() ? "codex-default" : config.model;
        String key = "windblog:" + operation + ":" + java.util.UUID.randomUUID();
        return client.inferDetailed(config, operation, profileId, input, key, java.util.UUID.randomUUID().toString());
    }

    private AiResult contentResult(JsonNode output) {
        AiResult result = new AiResult();
        if (output == null || output.isNull()) return result;
        if (output.isObject()) output.fields().forEachRemaining(entry -> result.contents.put(entry.getKey(), entry.getValue().asText(entry.getValue().toString())));
        else result.contents.put("default", output.asText(output.toString()));
        result.rawResponse = output.toString();
        return result;
    }

    private AiResult moderationResult(JsonNode output) {
        AiResult result = contentResult(output);
        if (output != null && output.isObject()) {
            if (output.has("isSafe")) result.isSafe = output.get("isSafe").asBoolean();
            if (output.has("safe")) result.isSafe = output.get("safe").asBoolean();
            if (output.has("score")) result.score = output.get("score").asInt();
            if (output.has("reason")) result.reason = output.get("reason").asText();
        }
        return result;
    }

    private AiResult contentResultWithProvenance(CodexCreatorInference inference) {
        AiResult result = contentResult(inference.output());
        applyProvenance(result, inference);
        return result;
    }

    private AiResult moderationResultWithProvenance(CodexCreatorInference inference) {
        AiResult result = moderationResult(inference.output());
        applyProvenance(result, inference);
        return result;
    }

    private void applyProvenance(AiResult result, CodexCreatorInference inference) {
        result.provider = "CODEX_CREATOR";
        result.taskId = inference.taskId();
        if (inference.usage() != null && inference.usage().isObject()) {
            result.inputTokens = inference.usage().path("inputTokens").asInt(0);
            result.outputTokens = inference.usage().path("outputTokens").asInt(0);
            result.totalTokens = inference.usage().path("totalTokens").asInt(result.inputTokens + result.outputTokens);
        }
        if (inference.provenance() != null && inference.provenance().isObject()) {
            inference.provenance().fields().forEachRemaining(entry -> result.provenance.put(entry.getKey(), entry.getValue()));
            result.modelId = inference.provenance().path("model").asText(null);
        }
        result.generationMode = "AUTOMATIC";
    }
}
