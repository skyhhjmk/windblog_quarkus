package com.biliwind.blog.controller.api.admin.dto.storage;

public record StorageProviderUpdateRequest(
        String displayName,
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
