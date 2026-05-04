package com.biliwind.blog.service.ai;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface AiService {
    /**
     * Generate content summary using AI.
     */
    CompletionStage<AiResult> summarize(com.biliwind.blog.model.AiProviderConfig config, Map<String, String> content);

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
}
