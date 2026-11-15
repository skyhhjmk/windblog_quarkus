package com.biliwind.blog.service.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral chat message used in multi-turn tool-calling conversations.
 */
public record AiChatMessage(String role, String content, List<AiToolCall> toolCalls,
                            String toolCallId, String name) {

    public AiChatMessage {
        role = role == null ? "user" : role.trim();
        toolCalls = toolCalls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }

    public static AiChatMessage system(String content) {
        return new AiChatMessage("system", content == null ? "" : content, List.of(), null, null);
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage("user", content == null ? "" : content, List.of(), null, null);
    }

    public static AiChatMessage assistant(String content, List<AiToolCall> toolCalls) {
        return new AiChatMessage("assistant", content, toolCalls, null, null);
    }

    public static AiChatMessage tool(String toolCallId, String name, String content) {
        return new AiChatMessage("tool", content == null ? "" : content, List.of(), toolCallId, name);
    }
}
