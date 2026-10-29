package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Normalises provider-specific streaming chunks into plain assistant text. */
public final class AiStreamText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiStreamText() {
    }

    public static String join(List<String> chunks) {
        StringBuilder text = new StringBuilder();
        if (chunks == null) {
            return "";
        }
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String value = chunk.trim();
            if (value.startsWith("data:")) {
                value = value.substring(5).trim();
            }
            if ("[DONE]".equals(value)) {
                continue;
            }
            if (value.startsWith("{")) {
                try {
                    JsonNode node = MAPPER.readTree(value);
                    if (node.has("type") && "error".equalsIgnoreCase(node.path("type").asText())) {
                        throw new IllegalStateException(node.path("content").asText("AI 提供商返回错误"));
                    }
                    if (node.has("content") && node.get("content").isTextual()) {
                        text.append(node.get("content").asText());
                        continue;
                    }
                    if (node.has("text") && node.get("text").isTextual()) {
                        text.append(node.get("text").asText());
                        continue;
                    }
                } catch (IllegalStateException exception) {
                    throw exception;
                } catch (Exception ignored) {
                    // Not a JSON envelope: keep the raw chunk below.
                }
            }
            text.append(value);
        }
        return text.toString().trim();
    }
}
