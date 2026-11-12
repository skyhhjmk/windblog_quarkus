package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class AiSelectionService {
    @Inject
    SettingsReader settings;

    @Inject
    ObjectMapper objectMapper;

    public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
        if (configs == null || configs.isEmpty()) return null;
        String selectedId = configuredId(operation);
        if (selectedId != null) {
            try {
                long id = Long.parseLong(selectedId);
                for (AiProviderConfig config : configs) {
                    if (config.id != null && config.id == id && config.enabled && allows(config, operation)) {
                        return config;
                    }
                }
            } catch (NumberFormatException ignored) {
                // An invalid explicit selection must not silently route to another provider.
            }
            return null;
        }
        for (AiProviderConfig config : configs) {
            if (config.enabled && allows(config, operation)) return config;
        }
        return null;
    }

    /** Selects an explicitly enabled legacy provider for opt-in degradation. */
    public AiProviderConfig fallback(String operation, List<AiProviderConfig> configs,
                                     AiProviderConfig primary) {
        if (configuredId(operation) != null) return null;
        if (configs == null) return null;
        for (AiProviderConfig config : configs) {
            if (config != primary && config.enabled
                    && allows(config, operation)) return config;
        }
        return null;
    }

    private boolean allows(AiProviderConfig config, String operation) {
        if (config.config == null || config.config.isBlank() || operation == null) return true;
        try {
            JsonNode operations = objectMapper.readTree(config.config).get("operations");
            if (operations == null) return true;
            if (operations.isArray()) {
                for (JsonNode item : operations) {
                    if (operation.equalsIgnoreCase(item.asText())) return true;
                    if ("slug".equalsIgnoreCase(operation)
                            && ("translate".equalsIgnoreCase(item.asText()) || "assistant".equalsIgnoreCase(item.asText()))) {
                        return true;
                    }
                }
                return false;
            }
            if (operations.isTextual()) {
                String text = operations.asText();
                return operation.equalsIgnoreCase(text)
                        || ("slug".equalsIgnoreCase(operation)
                                && ("translate".equalsIgnoreCase(text) || "assistant".equalsIgnoreCase(text)));
            }
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    String configuredId(String operation) {
        if (operation == null) return null;
        try {
            String selected = settings.text(SettingKey.AI_OPERATION_CONFIGS, operation);
            if (selected != null && !selected.isBlank()) {
                return selected.trim();
            }
            if ("slug".equalsIgnoreCase(operation)) {
                String translate = settings.text(SettingKey.AI_OPERATION_CONFIGS, "translate");
                if (translate != null && !translate.isBlank()) {
                    return translate.trim();
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
