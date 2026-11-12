package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Iterator;
import java.util.Map;

/**
 * The typed read seam for database-backed settings. Callers name a catalogue
 * key and field; this class owns the canonical default and type validation, so
 * no consumer re-parses JSON or re-declares a fallback.
 */
@ApplicationScoped
public class SettingsReader {

    private final ConfigManager configManager;
    private final RegionalSettings regionalSettings;

    @Inject
    public SettingsReader(ConfigManager configManager, RegionalSettings regionalSettings) {
        this.configManager = configManager;
        this.regionalSettings = regionalSettings;
    }

    public JsonNode raw(SettingKey key) {
        return configManager.get(key.key());
    }

    /** Raw value from L1 only; safe on the Vert.x IO thread. */
    public JsonNode localRaw(SettingKey key) {
        return configManager.getLocal(key.key());
    }

    /** The stored object with every missing catalogue default filled in. */
    public JsonNode object(SettingKey key) {
        SettingDefinition definition = SettingCatalogue.find(key);
        JsonNode stored = configManager.get(key.key());
        return mergeDefaults(definition, stored);
    }

    public String text(SettingKey key, String field) {
        SettingDefinition definition = SettingCatalogue.find(key);
        JsonNode value = configManager.get(key.key());
        JsonNode node = value != null && value.isObject() ? value.get(field) : null;
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return defaultText(definition, field);
    }

    /** Like {@link #text} but a stored blank value falls back to the declared default. */
    public String textNonBlank(SettingKey key, String field) {
        String value = text(key, field);
        return value == null || value.isBlank() ? defaultText(SettingCatalogue.find(key), field) : value;
    }

    public boolean flag(SettingKey key, String field) {
        SettingDefinition definition = SettingCatalogue.find(key);
        JsonNode value = configManager.get(key.key());
        JsonNode node = value != null && value.isObject() ? value.get(field) : null;
        if (node != null && node.isBoolean()) {
            return node.asBoolean();
        }
        if (node != null && node.isTextual()) {
            String text = node.asText().trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return defaultBoolean(definition, field);
    }

    public int number(SettingKey key, String field) {
        SettingDefinition definition = SettingCatalogue.find(key);
        JsonNode value = configManager.get(key.key());
        JsonNode node = value != null && value.isObject() ? value.get(field) : null;
        if (node != null) {
            if (node.canConvertToInt()) {
                return node.asInt();
            }
            if (node.isTextual()) {
                try {
                    return Integer.parseInt(node.asText().trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the declared default
                }
            }
        }
        return defaultInt(definition, field);
    }

    public JsonNode regionalObject(SettingKey key, BlogRegion region) {
        JsonNode value = regionalSettings.object(key, region);
        if (value != null && value.isObject()) {
            return mergeDefaults(SettingCatalogue.find(key), value);
        }
        return value;
    }

    public String regionalText(SettingKey key, String field, BlogRegion region) {
        JsonNode value = regionalSettings.object(key, region);
        if (value != null && value.isObject() && value.has(field) && value.get(field).isTextual()) {
            return value.get(field).asText();
        }
        return defaultText(SettingCatalogue.find(key), field);
    }

    public String language(BlogRegion region) {
        return regionalSettings.language(region);
    }

    public String languageCached(BlogRegion region) {
        return regionalSettings.languageCached(region);
    }

    public boolean hasLanguageOverride(BlogRegion region) {
        return regionalSettings.hasLanguageOverride(region);
    }

    public String favicon(BlogRegion region) {
        return regionalSettings.favicon(region);
    }

    public String siteUrl(BlogRegion region) {
        return regionalSettings.siteUrl(region);
    }

    public JsonNode resolved(SettingKey key, BlogRegion region) {
        return regionalSettings.resolved(key, region);
    }

    public boolean containsSecret(SettingKey key, JsonNode value) {
        return SettingSecrets.containsSecret(definitionOrNull(key), value);
    }

    public JsonNode masked(SettingKey key, JsonNode value) {
        return SettingSecrets.mask(definitionOrNull(key), value);
    }

    public JsonNode mergeSecretValues(SettingKey key, JsonNode current, JsonNode submitted) {
        return SettingSecrets.mergeSecretValues(definitionOrNull(key), current, submitted);
    }

    public SettingDefinition definition(SettingKey key) {
        return SettingCatalogue.find(key);
    }

    private static SettingDefinition definitionOrNull(SettingKey key) {
        return key == null ? null : SettingCatalogue.find(key);
    }

    private static JsonNode mergeDefaults(SettingDefinition definition, JsonNode stored) {
        JsonNode defaultValue = definition.defaultValue();
        if (stored == null || !stored.isObject()) {
            return defaultValue.deepCopy();
        }
        ObjectNode merged = ((ObjectNode) stored).deepCopy();
        if (defaultValue.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = defaultValue.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!merged.has(field.getKey())) {
                    merged.set(field.getKey(), field.getValue().deepCopy());
                }
            }
        }
        return merged;
    }

    private static String defaultText(SettingDefinition definition, String field) {
        JsonNode node = definition.defaultField(field);
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static boolean defaultBoolean(SettingDefinition definition, String field) {
        JsonNode node = definition.defaultField(field);
        return node != null && node.isBoolean() && node.asBoolean();
    }

    private static int defaultInt(SettingDefinition definition, String field) {
        JsonNode node = definition.defaultField(field);
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }
}
