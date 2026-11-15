package com.biliwind.blog.service.ai;

/**
 * Callback used by {@link AiCallExecutor} to execute model-requested tool calls
 * between completion rounds.
 */
@FunctionalInterface
public interface AiToolExecutor {

    String execute(AiToolCall call, AiToolContext context);
}
