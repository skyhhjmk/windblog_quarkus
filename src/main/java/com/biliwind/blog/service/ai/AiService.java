package com.biliwind.blog.service.ai;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface AiService {
    /**
     * Generate content summary using AI.
     */
    CompletionStage<AiResult> summarize(com.biliwind.blog.model.AiProviderConfig config, Map<String, String> content);

    /**
     * Translate structured article fields while preserving their names and markup.
     */
    default CompletionStage<AiResult> translate(com.biliwind.blog.model.AiProviderConfig config,
            String sourceLanguage, String targetLanguage, Map<String, String> fields) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("该 AI 提供商不支持文章翻译"));
    }

    /**
     * Moderate content using AI (e.g. for spam or toxic content).
     */
    CompletionStage<AiResult> moderate(com.biliwind.blog.model.AiProviderConfig config, String prompt, String content);

    /**
     * 流式交互测试接口
     */
    io.smallrye.mutiny.Multi<String> testStream(com.biliwind.blog.model.AiProviderConfig config, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request);

    /**
     * Returns true if this service implementation supports this provider vendor type from config.
     */
    boolean supports(com.biliwind.blog.model.AiProviderConfig config);

    /**
     * Fetch list of available models from the provider.
     */
    default java.util.concurrent.CompletionStage<java.util.List<String>> fetchModels(com.biliwind.blog.model.AiProviderConfig config) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
    }
}
