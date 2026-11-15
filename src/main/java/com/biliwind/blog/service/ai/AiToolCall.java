package com.biliwind.blog.service.ai;

/**
 * One tool invocation requested by an AI model in a chat completion response.
 */
public record AiToolCall(String id, String name, String argumentsJson) {

    public AiToolCall {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }
}
