package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Built-in AI tool contract. Implementations are discovered via CDI and exposed
 * alongside any configured external MCP server tools.
 */
public interface AiTool {

    AiToolDefinition definition();

    String execute(JsonNode arguments, AiToolContext context) throws Exception;
}
