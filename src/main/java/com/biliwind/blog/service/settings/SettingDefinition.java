package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.SettingLevel;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * The single declaration of a database-backed setting. Seeding, defaults,
 * validation, masking, authorization and regional resolution all read this.
 */
public record SettingDefinition(
        SettingKey key,
        JsonNode defaultValue,
        String configType,
        String group,
        JsonNode uiSchema,
        String description,
        SettingLevel level,
        boolean secret,
        Set<String> secretFields,
        RegionalKind regionalKind) {

    public SettingDefinition {
        secretFields = secretFields == null ? Set.of() : Set.copyOf(secretFields);
        regionalKind = regionalKind == null ? RegionalKind.NONE : regionalKind;
        level = level == null ? SettingLevel.NORMAL : level;
    }

    public boolean requiresVerification() {
        return level == SettingLevel.CRITICAL;
    }

    public JsonNode defaultField(String field) {
        if (defaultValue == null || !defaultValue.isObject()) {
            return null;
        }
        return defaultValue.get(field);
    }
}
