package com.biliwind.blog.controller.api.admin.dto;

import com.biliwind.blog.model.AiProviderConfig;

import java.time.OffsetDateTime;

public class AdminAiProviderDtos {

    public record AiProviderConfigDto(
            Long id,
            com.biliwind.blog.model.AiConfigType type,
            String name,
            String provider,
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            String config,
            OffsetDateTime updatedAt
    ) {

        public static AiProviderConfigDto of(AiProviderConfig config) {
            return new AiProviderConfigDto(
                    config.id,
                    config.type,
                    config.name,
                    config.provider,
                    config.enabled,
                    config.endpoint,
                    config.model,
                    config.apiKey,
                    config.config,
                    config.updatedAt
            );
        }
    }

    public record AiProviderConfigUpdateRequest(
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
