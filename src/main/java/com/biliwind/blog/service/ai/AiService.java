package com.biliwind.blog.service.ai;

import com.biliwind.blog.controller.api.admin.dto.AiTestRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface AiService {
    /**
     * Generate content summary using AI.
     */
    CompletionStage<AiResult> summarize(com.biliwind.blog.model.AiProviderConfig config, Map<String, String> content);

    /**
     * Translate structured article fields using the default translation prompt.
     */
    default CompletionStage<AiResult> translate(com.biliwind.blog.model.AiProviderConfig config,
            String sourceLanguage, String targetLanguage, Map<String, String> fields) {
        return translate(config, sourceLanguage, targetLanguage, fields, null);
    }

    /**
     * Translate structured article fields while preserving their names and markup.
     */
    default CompletionStage<AiResult> translate(com.biliwind.blog.model.AiProviderConfig config,
            String sourceLanguage, String targetLanguage, Map<String, String> fields,
            String promptTemplate) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("该 AI 提供商不支持文章翻译"));
    }

    /**
     * Moderate content using AI (e.g. for spam or toxic content).
     */
    CompletionStage<AiResult> moderate(com.biliwind.blog.model.AiProviderConfig config, String prompt, String content);

    /**
     * Free-form completion used by admin writing assistants. Implementations
     * that expose a native chat endpoint should override this to avoid the
     * test-stream adapter.
     */
    default CompletionStage<AiResult> complete(com.biliwind.blog.model.AiProviderConfig config,
            String systemPrompt, String prompt) {
        return testStream(config, new AiTestRequest(prompt, systemPrompt, false, null, false))
                .collect().asList()
                .convert().toCompletionStage()
                .thenApply(chunks -> {
                    String text = AiStreamText.join(chunks);
                    if (text.isBlank()) {
                        throw new IllegalStateException("AI 提供商未返回写作结果");
                    }
                    AiResult result = new AiResult();
                    result.contents.put("text", text);
                    return result;
                });
    }

    /**
     * 流式交互测试接口
     */
    io.smallrye.mutiny.Multi<String> testStream(com.biliwind.blog.model.AiProviderConfig config, AiTestRequest request);

    /**
     * Returns true if this service implementation supports this provider vendor type from config.
     */
    boolean supports(com.biliwind.blog.model.AiProviderConfig config);

    /**
     * Fetch list of available models from the provider.
     */
    default CompletionStage<List<String>> fetchModels(com.biliwind.blog.model.AiProviderConfig config) {
        return CompletableFuture.completedFuture(List.of());
    }
}
