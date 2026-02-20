package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProvider;
import com.biliwind.blog.model.AiProviderConfig;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AiProviderConfigService {

    public List<AiProviderConfig> listAll() {
        return AiProviderConfig.listAll(Sort.by("provider"));
    }

    public Optional<AiProviderConfig> getByProvider(AiProvider provider) {
        if (provider == null) {
            return Optional.empty();
        }
        return AiProviderConfig.find("provider", provider.getKey())
                .firstResultOptional();
    }

    @Transactional
    public AiProviderConfig upsert(String providerKey, ConfigUpdate update) {
        AiProvider provider = AiProvider.from(providerKey);
        if (provider == null) {
            throw new IllegalArgumentException("未知的 AI 提供商: " + providerKey);
        }

        AiProviderConfig config = AiProviderConfig.find("provider", provider.getKey())
                .firstResult();
        if (config == null) {
            config = new AiProviderConfig();
            config.provider = provider.getKey();
            config.enabled = update.enabled() != null && update.enabled();
        }

        if (update.enabled() != null) {
            config.enabled = update.enabled();
        }
        if (update.endpoint() != null) {
            config.endpoint = update.endpoint().trim();
        }
        if (update.apiKey() != null) {
            config.apiKey = update.apiKey().trim();
        }
        if (update.model() != null) {
            config.model = update.model().trim();
        }

        config.updatedAt = OffsetDateTime.now();
        config.persist();
        return config;
    }

    public record ConfigUpdate(
            Boolean enabled,
            String endpoint,
            String apiKey,
            String model
    ) {
    }
}
