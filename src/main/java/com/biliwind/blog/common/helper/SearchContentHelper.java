package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.PostRenderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.util.Iterator;
import java.util.Map;

public final class SearchContentHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SearchContentHelper() {
    }

    public static String toSearchableText(String content, PostRenderType renderType) {
        if (content == null || content.isBlank()) {
            return "";
        }

        PostRenderType effectiveRenderType = renderType;
        if (effectiveRenderType == null) {
            effectiveRenderType = PostRenderType.MARKDOWN;
        }

        if (effectiveRenderType == PostRenderType.MARKDOWN
                || effectiveRenderType == PostRenderType.FLUTTER_MARKDOWN_PLUS) {
            return Jsoup.parse(MarkdownHelper.toHtml(content)).text();
        }

        String jsonText = extractJsonText(content);
        if (!jsonText.isBlank()) {
            return jsonText;
        }

        return Jsoup.parse(content).text();
    }

    private static String extractJsonText(String content) {
        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("{") && !trimmedContent.startsWith("[")) {
            return "";
        }

        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(trimmedContent);
            StringBuilder searchableText = new StringBuilder();
            appendJsonText(rootNode, searchableText);
            return searchableText.toString().trim();
        } catch (Exception exception) {
            return "";
        }
    }

    private static void appendJsonText(JsonNode node, StringBuilder searchableText) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isTextual()) {
            appendText(searchableText, node.asText());
            return;
        }

        if (node.isArray()) {
            for (JsonNode childNode : node) {
                appendJsonText(childNode, searchableText);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            if (isFormattingField(fieldName)) {
                continue;
            }
            appendJsonText(field.getValue(), searchableText);
        }
    }

    private static boolean isFormattingField(String fieldName) {
        return "attributes".equals(fieldName)
                || "style".equals(fieldName)
                || "type".equals(fieldName)
                || "id".equals(fieldName);
    }

    private static void appendText(StringBuilder searchableText, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (searchableText.length() > 0) {
            searchableText.append(' ');
        }
        searchableText.append(text.trim());
    }
}
