package com.biliwind.blog.service.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operation-neutral definition of a tool (function / MCP tool) exposed to an AI model.
 */
public record AiToolDefinition(String name, String description, Map<String, Object> parameters) {

    public AiToolDefinition {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        parameters = parameters == null
                ? Map.of("type", "object", "properties", Map.of())
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
