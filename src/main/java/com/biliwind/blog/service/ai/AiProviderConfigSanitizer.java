package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;

/** Keeps provider configuration secrets out of administrator responses and masked updates. */
public final class AiProviderConfigSanitizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiProviderConfigSanitizer() {
    }

    public static String maskConfig(String config) {
        if (config == null || config.isBlank()) {
            return config;
        }
        try {
            return maskNode(OBJECT_MAPPER.readTree(config)).toString();
        } catch (Exception exception) {
            return "{}";
        }
    }

    public static String mergeMaskedSecrets(String submittedConfig, String existingConfig) {
        try {
            JsonNode submitted = OBJECT_MAPPER.readTree(submittedConfig);
            JsonNode existing = existingConfig == null || existingConfig.isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(existingConfig);
            return mergeNode(submitted, existing).toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI 配置 JSON 格式无效", exception);
        }
    }

    private static JsonNode maskNode(JsonNode node) {
        if (node == null) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    result.set(field.getKey(), TextNode.valueOf("******"));
                } else {
                    result.set(field.getKey(), maskNode(field.getValue()));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                result.add(maskNode(item));
            }
            return result;
        }
        return node.deepCopy();
    }

    private static JsonNode mergeNode(JsonNode submitted, JsonNode existing) {
        if (submitted == null || submitted.isNull()) {
            return existing == null ? OBJECT_MAPPER.createObjectNode() : existing.deepCopy();
        }
        if (submitted.isObject() && existing != null && existing.isObject()) {
            ObjectNode result = (ObjectNode) existing.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = submitted.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode existingValue = existing.get(field.getKey());
                if (isSensitiveField(field.getKey()) && isMaskedValue(field.getValue())) {
                    if (existingValue != null) {
                        result.set(field.getKey(), existingValue.deepCopy());
                    }
                } else {
                    result.set(field.getKey(), mergeNode(field.getValue(), existingValue));
                }
            }
            return result;
        }
        if (submitted.isArray()) {
            ArrayNode result = OBJECT_MAPPER.createArrayNode();
            for (int index = 0; index < submitted.size(); index++) {
                JsonNode existingValue = existing != null && existing.isArray() && existing.size() > index
                        ? existing.get(index) : null;
                result.add(mergeNode(submitted.get(index), existingValue));
            }
            return result;
        }
        return submitted.deepCopy();
    }

    private static boolean isSensitiveField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase();
        String compact = normalized.replace("_", "").replace("-", "");
        return compact.contains("password") || compact.contains("secret")
                || compact.contains("token") || compact.contains("apikey")
                || compact.contains("accesskey") || compact.contains("privatekey")
                || compact.equals("key");
    }

    private static boolean isMaskedValue(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return false;
        }
        String value = node.asText();
        return "******".equals(value) || "sk-****".equals(value) || value.endsWith("******");
    }
}
