package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Decides where the topic module keeps its data.
 *
 * <p>When the administrator pins the topic operation to a normal AI provider or
 * a polling group, the topic module must not call Codex Creator at all — the
 * provider is used for generation and WindBlog owns the topic pool. Otherwise
 * the existing shared Codex Creator pool remains the storage backend.</p>
 */
@ApplicationScoped
public class AiTopicModeService {

    @Inject
    CodexCreatorConnectionService connectionService;

    /** Returns the provider id pinned to the topic operation, or null when left on automatic. */
    public Long configuredTopicProviderId() {
        try {
            SystemSetting setting = SystemSetting.findByKey("ai_operation_configs");
            if (setting == null || setting.configValue == null) return null;
            JsonNode selected = setting.configValue.get("topic");
            if (selected == null || selected.isNull() || selected.asText().isBlank()) return null;
            return Long.parseLong(selected.asText().trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public AiProviderConfig configuredTopicProvider() {
        Long id = configuredTopicProviderId();
        return id == null ? null : AiProviderConfig.findById(id);
    }

    /** True when the topic module should use WindBlog's local topic pool. */
    public boolean localMode() {
        if (!codexCreatorConfigured()) {
            // Without a shared secret the Codex pool is unusable; never surface
            // "shared secret is not configured" from the topic module.
            return true;
        }
        AiProviderConfig provider = configuredTopicProvider();
        return provider != null && provider.enabled
                && !CodexCreatorConnectionService.PROVIDER.equalsIgnoreCase(provider.provider);
    }

    public boolean isCodexProvider(Long providerId) {
        if (providerId == null) return false;
        AiProviderConfig provider = AiProviderConfig.findById(providerId);
        return provider != null
                && CodexCreatorConnectionService.PROVIDER.equalsIgnoreCase(provider.provider);
    }

    boolean codexCreatorConfigured() {
        try {
            return connectionService.current().sharedSecretConfigured();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
