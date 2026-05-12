package com.biliwind.blog.controller.api.admin.dto.storage;

import com.biliwind.blog.model.StorageProviderEntity;

public record StorageProviderResponse(
        Long id,
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
    public static StorageProviderResponse fromEntity(StorageProviderEntity entity) {
        return new StorageProviderResponse(
                entity.id,
                entity.name,
                entity.displayName,
                entity.providerType,
                entity.isEnabled,
                entity.isPrimary,
                entity.role,
                entity.configJson,
                entity.supportedTypes,
                entity.cdnDomain,
                entity.cdnEnabled,
                entity.region,
                entity.priority
        );
    }
}
