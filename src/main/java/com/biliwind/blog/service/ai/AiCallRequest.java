package com.biliwind.blog.service.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caller-facing description of one AI call: the operation plus its payload.
 * Callers never select transports, manage retries, or account usage.
 */
public record AiCallRequest(AiOperation operation, Long providerId, String operationTag,
                            String systemPrompt, String prompt, String sourceLanguage,
                            String targetLanguage, String promptTemplate,
                            Map<String, String> structuredValues, List<String> imageUrls,
                            boolean stream) {

    public AiCallRequest {
        structuredValues = structuredValues == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(structuredValues));
        imageUrls = imageUrls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(imageUrls));
    }

    public static AiCallRequest summarize(Map<String, String> contentByLanguage) {
        return new AiCallRequest(AiOperation.SUMMARIZE, null, "summarize", null, null,
                null, null, null, contentByLanguage, List.of(), false);
    }

    public static AiCallRequest translate(String sourceLanguage, String targetLanguage,
                                          Map<String, String> fields) {
        return translate(sourceLanguage, targetLanguage, fields, null);
    }

    public static AiCallRequest translate(String sourceLanguage, String targetLanguage,
                                          Map<String, String> fields, String promptTemplate) {
        return new AiCallRequest(AiOperation.TRANSLATE, null, "translate", null, null,
                sourceLanguage, targetLanguage, promptTemplate, fields, List.of(), false);
    }

    public static AiCallRequest moderate(String content) {
        return new AiCallRequest(AiOperation.MODERATE, null, "moderate", null,
                content == null ? "" : content, null, null, null, Map.of(), List.of(), false);
    }

    public static AiCallRequest complete(Long providerId, String operationTag,
                                         String systemPrompt, String prompt) {
        return new AiCallRequest(AiOperation.COMPLETE, providerId,
                operationTag == null || operationTag.isBlank() ? "assistant" : operationTag.trim(),
                systemPrompt, prompt, null, null, null, Map.of(), List.of(), false);
    }

    public static AiCallRequest completeStream(Long providerId, String systemPrompt, String prompt) {
        return new AiCallRequest(AiOperation.COMPLETE_STREAM, providerId, "assistant",
                systemPrompt, prompt, null, null, null, Map.of(), List.of(), true);
    }

    public static AiCallRequest testStream(String prompt, String systemPrompt,
                                           List<String> imageUrls, boolean stream) {
        return new AiCallRequest(AiOperation.TEST_STREAM, null, "test", systemPrompt, prompt,
                null, null, null, Map.of(), imageUrls, stream);
    }

    public AiCallRequest withPromptTemplate(String template) {
        return new AiCallRequest(operation, providerId, operationTag, systemPrompt, prompt,
                sourceLanguage, targetLanguage, template, structuredValues, imageUrls, stream);
    }
}
