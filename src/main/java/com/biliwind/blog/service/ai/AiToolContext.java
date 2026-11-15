package com.biliwind.blog.service.ai;

/**
 * Execution context passed to AI tools (built-in or MCP) during a generation run.
 */
public record AiToolContext(Long operatorId, Long topicId, boolean allowNetwork, boolean allowMediaImport) {

    public static AiToolContext forDraft(Long operatorId, Long topicId) {
        return new AiToolContext(operatorId, topicId, true, true);
    }
}
