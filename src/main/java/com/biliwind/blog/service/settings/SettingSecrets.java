package com.biliwind.blog.service.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Locale;

/**
 * Masking, secret detection and redacted-value merging. The per-setting
 * decision comes from the catalogue; the field-name set covers nested
 * structures (for example a provider object with an {@code apiKey}).
 */
public final class SettingSecrets {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDACTED = "***REDACTED***";

    private SettingSecrets() {
    }

    public static boolean isSecretFieldName(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("key")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("credential");
    }

    public static boolean containsSecret(SettingDefinition definition, JsonNode value) {
        if (definition != null) {
            if (definition.secret()) {
                return true;
            }
            for (String field : definition.secretFields()) {
                if (containsField(value, field)) {
                    return true;
                }
            }
        }
        return containsSecretField(value);
    }

    public static JsonNode mask(SettingDefinition definition, JsonNode value) {
        if (value == null) {
            return null;
        }
        if (definition != null && definition.secret()) {
            return TextNode.valueOf(REDACTED);
        }
        if (!value.isObject()) {
            if (value.isArray()) {
                var result = MAPPER.createArrayNode();
                for (JsonNode item : value) {
                    result.add(mask(definition, item));
                }
                return result;
            }
            return value.deepCopy();
        }
        ObjectNode result = MAPPER.createObjectNode();
        value.fields().forEachRemaining(field -> {
            if (isDeclaredSecret(definition, field.getKey())) {
                result.put(field.getKey(), REDACTED);
            } else {
                result.set(field.getKey(), mask(null, field.getValue()));
            }
        });
        return result;
    }

    public static JsonNode mergeSecretValues(SettingDefinition definition, JsonNode current, JsonNode submitted) {
        if (submitted == null || submitted.isNull()) {
            return current == null ? MAPPER.createObjectNode() : current.deepCopy();
        }
        if (definition != null && definition.secret()) {
            return isRedacted(submitted) ? copy(current) : submitted.deepCopy();
        }
        if (!submitted.isObject() || current == null || !current.isObject()) {
            return submitted.deepCopy();
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        submitted.fields().forEachRemaining(field -> {
            String name = field.getKey();
            if (isDeclaredSecret(definition, name)) {
                if (isRedacted(field.getValue())) {
                    merged.set(name, copy(current.get(name)));
                } else {
                    merged.set(name, field.getValue().deepCopy());
                }
            } else {
                merged.set(name, mergeSecretValues(null, current.get(name), field.getValue()));
            }
        });
        return merged;
    }

    private static boolean isDeclaredSecret(SettingDefinition definition, String fieldName) {
        return isSecretFieldName(fieldName)
                || (definition != null && definition.secretFields().contains(fieldName));
    }

    private static boolean containsField(JsonNode value, String fieldName) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().equals(fieldName) || containsField(entry.getValue(), fieldName)) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsField(item, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSecretField(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (isSecretFieldName(entry.getKey()) || containsSecretField(entry.getValue())) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsSecretField(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRedacted(JsonNode value) {
        return value == null || value.isNull()
                || value.isTextual() && (value.asText().isBlank() || REDACTED.equals(value.asText()));
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? MAPPER.nullNode() : value.deepCopy();
    }
}
