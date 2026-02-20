package com.biliwind.blog.controller.api.admin.dto;

import com.biliwind.blog.model.AiProviderConfig;

import java.time.OffsetDateTime;

public class AdminAiProviderDtos {

    public record AiProviderConfigDto(
            String provider,
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            OffsetDateTime updatedAt
    ) {

        public static AiProviderConfigDto of(AiProviderConfig config) {
            return new AiProviderConfigDto(
                    config.provider,
                    config.enabled,
                    config.endpoint,
                    config.model,
                    config.apiKey,
                    config.updatedAt
            );
        }
    }

    public record AiProviderConfigUpdateRequest(
            Boolean enabled,
            String endpoint,
            String apiKey,
            String model
    ) {
    }
}
