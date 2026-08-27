package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** Runtime result envelope retained at the WindBlog integration boundary. */
public record CodexCreatorInference(JsonNode output, String taskId, JsonNode usage, JsonNode provenance) {
}
