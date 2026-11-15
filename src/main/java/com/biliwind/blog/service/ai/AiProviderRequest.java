package com.biliwind.blog.service.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Operation-neutral request handed to a provider adapter. Provider-specific
 * wire formats stay in the adapter; everything that makes the call correct
 * (timeouts, retries, limits, accounting, context) belongs to the AI module.
 */
public record AiProviderRequest(String operation, String systemPrompt, String prompt,
                                boolean jsonResponse, List<String> imageUrls, boolean stream,
                                List<AiChatMessage> messages, List<AiToolDefinition> tools) {

    public AiProviderRequest(String operation, String systemPrompt, String prompt,
                             boolean jsonResponse, List<String> imageUrls, boolean stream) {
        this(operation, systemPrompt, prompt, jsonResponse, imageUrls, stream, List.of(), List.of());
    }

    public AiProviderRequest {
        imageUrls = imageUrls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(imageUrls));
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        tools = tools == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public static AiProviderRequest text(String operation, String systemPrompt, String prompt) {
        return new AiProviderRequest(operation, systemPrompt, prompt, false, List.of(), false, List.of(), List.of());
    }

    public static AiProviderRequest json(String operation, String prompt) {
        return new AiProviderRequest(operation, null, prompt, true, List.of(), false, List.of(), List.of());
    }

    public static AiProviderRequest stream(String operation, String systemPrompt, String prompt,
                                           List<String> imageUrls, boolean stream) {
        return new AiProviderRequest(operation, systemPrompt, prompt, false, imageUrls, stream, List.of(), List.of());
    }

    public static AiProviderRequest chatWithTools(String operation, List<AiChatMessage> messages,
                                                  List<AiToolDefinition> tools) {
        return new AiProviderRequest(operation, null, null, false, List.of(), false, messages, tools);
    }
}
