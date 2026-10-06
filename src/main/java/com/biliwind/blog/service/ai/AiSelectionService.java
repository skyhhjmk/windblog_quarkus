package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class AiSelectionService {
    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "windblog.ai.codex-creator.prefer", defaultValue = "true")
    boolean preferCodexCreator;

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
        if (preferCodexCreator) {
            for (AiProviderConfig config : configs) {
                if (config.enabled && "CODEX_CREATOR".equalsIgnoreCase(config.provider)
                        && allows(config, operation)) return config;
            }
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
                    && !"CODEX_CREATOR".equalsIgnoreCase(config.provider)
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
                }
                return false;
            }
            return !operations.isTextual() || operation.equalsIgnoreCase(operations.asText());
        } catch (Exception ignored) {
            return true;
        }
    }

    private String configuredId(String operation) {
        if (operation == null) return null;
        SystemSetting setting = SystemSetting.findByKey("ai_operation_configs");
        if (setting == null || setting.configValue == null) return null;
        JsonNode selected = setting.configValue.get(operation);
        if (selected == null || selected.isNull() || selected.asText().isBlank()) return null;
        return selected.asText().trim();
    }
}
