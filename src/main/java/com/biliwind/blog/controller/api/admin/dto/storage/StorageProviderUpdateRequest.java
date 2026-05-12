package com.biliwind.blog.controller.api.admin.dto.storage;

public record StorageProviderUpdateRequest(
        String displayName,
        Boolean isEnabled,
        String configJson,
        String supportedTypes,
        String cdnDomain,
        Boolean cdnEnabled,
        String region,
        Integer priority
) {
}
