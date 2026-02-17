package com.biliwind.blog.service.ai;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface AiService {
    /**
     * Generate content summary using AI.
     * 
     * @param content The content to summarize (key is language, value is content)
     * @return A map where key is language and value is the summary
     */
    CompletionStage<Map<String, String>> summarize(Map<String, String> content);

    /**
     * Check if the service is available.
     * 
     * @return true if available
     */
    boolean isAvailable();

    /**
     * Service priority (lower is higher priority for failover)
     * 
     * @return priority level
     */
    int getPriority();

    /**
     * Moderate content using AI (e.g. for spam or toxic content).
     * 
     * @param content The content to moderate
     * @return A completion stage that returns true if the content is safe, false
     *         otherwise
     */
    CompletionStage<Boolean> moderate(String content);
}
