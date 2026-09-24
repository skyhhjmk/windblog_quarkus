package com.biliwind.blog.controller.api.admin.dto.storage;

public record StorageClassUpdateRequest(
        String displayName,
        Boolean isEnabled,
        Boolean isPrimary,
        String role,
        String configJson,
        String supportedTypes,
        String cdnDomain,
        Boolean cdnEnabled,
        String serviceRegion,
        java.util.List<String> contentRegions,
        java.util.List<String> excludedContentRegions,
        Boolean allowEncryptedBackup,
        Integer priority
) {
}
