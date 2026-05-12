package com.biliwind.blog.controller.api.admin.dto.storage;

public record StorageProviderCreateRequest(
        String name,
        String displayName,
        String providerType,
        Boolean isEnabled,
        Boolean isPrimary,
        String role,
        String configJson,
        String supportedTypes,
        String cdnDomain,
        Boolean cdnEnabled,
        String region,
        Integer priority
) {
}
