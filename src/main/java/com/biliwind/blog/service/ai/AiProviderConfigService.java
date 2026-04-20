package com.biliwind.blog.service.ai;

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
        return AiProviderConfig.listAll(Sort.by("type", "name"));
    }

    public Optional<AiProviderConfig> getById(Long id) {
        if (id == null) return Optional.empty();
        return AiProviderConfig.findByIdOptional(id);
    }

    public Optional<AiProviderConfig> getByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return AiProviderConfig.find("name", name).firstResultOptional();
    }

    @Transactional
    public AiProviderConfig upsert(Long id, ConfigUpdate update) {
        AiProviderConfig config;
        if (id != null && id > 0) {
            config = AiProviderConfig.findById(id);
            if (config == null) {
                throw new IllegalArgumentException("未找到该 AI 配置 ID: " + id);
            }
        } else {
            config = new AiProviderConfig();
            config.type = update.type() != null ? update.type() : com.biliwind.blog.model.AiConfigType.PROVIDER;
            if (update.name() == null || update.name().isBlank()) {
                throw new IllegalArgumentException("AI 配置名称不能为空");
            }
            config.name = update.name().trim();
            // Provider vendor
            config.provider = update.provider() != null ? update.provider() : "UNKNOWN";
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
        if (update.config() != null) {
            config.config = update.config();
        }

        config.updatedAt = OffsetDateTime.now();
        if (config.id == null) {
            config.persist();
        }
        return config;
    }

    @Transactional
    public void delete(Long id) {
        AiProviderConfig.deleteById(id);
    }

    public record ConfigUpdate(
            com.biliwind.blog.model.AiConfigType type,
            String name,
            String provider,
            Boolean enabled,
            String endpoint,
            String apiKey,
            String model,
            String config
    ) {
    }
}
