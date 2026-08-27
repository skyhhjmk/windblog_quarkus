package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class AiSelectionService {
    @ConfigProperty(name = "windblog.ai.codex-creator.prefer", defaultValue = "true")
    boolean preferCodexCreator;

    public AiProviderConfig select(String operation, List<AiProviderConfig> configs) {
        if (configs == null || configs.isEmpty()) return null;
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
        return !config.config.contains("\"operations\"") || config.config.contains(operation);
    }
}
