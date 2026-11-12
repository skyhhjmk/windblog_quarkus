package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import io.smallrye.mutiny.Multi;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provider adapter seam. An adapter translates one operation-neutral request
 * into its vendor wire format; it does not own selection, retries, limits,
 * accounting or context. Those live in {@link AiCallExecutor}, so every
 * adapter — including the mock — takes the same path.
 */
public interface AiService {

    boolean supports(AiProviderConfig config);

    /** Non-streaming completion; the raw model text goes in {@code contents["text"]}. */
    CompletionStage<AiResult> complete(AiProviderConfig config, AiProviderRequest request);

    /** Streaming completion emitting the module's {@code {"type","content"}} chunk format. */
    default Multi<String> stream(AiProviderConfig config, AiProviderRequest request) {
        return Multi.createFrom().failure(
                new UnsupportedOperationException("该 AI 提供商不支持流式调用"));
    }

    /** Available models, when the provider exposes a list endpoint. */
    default CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        return CompletableFuture.completedFuture(List.of());
    }
}
