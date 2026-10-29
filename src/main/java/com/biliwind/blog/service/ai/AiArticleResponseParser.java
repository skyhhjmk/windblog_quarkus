package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict parser for API-provider article draft output. */
public final class AiArticleResponseParser {

    private AiArticleResponseParser() {
    }

    public static Article parse(ObjectMapper objectMapper, String text) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException("AI 未返回文章内容");
        }
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            value = value.substring(start, end + 1);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new BadRequestException("解析 AI 文章 JSON 失败");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("AI 返回的文章不是 JSON 对象");
        }
        String title = required(node, "title", 160);
        String content = required(node, "contentMarkdown", 100_000);
        String summary = truncate(node.path("summary").asText(""), 2000);
        if (summary.isBlank()) {
            summary = truncate(content.replace("*", "").replace("#", "").trim(), 200);
        }
        return new Article(title, summary, content);
    }

    private static String required(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new BadRequestException("AI 文章缺少字段: " + field);
        }
        return truncate(value, maxLength);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    public record Article(String title, String summary, String contentMarkdown) {
    }
}
