package com.biliwind.blog.controller.api.admin.dto.storage;

import com.biliwind.blog.model.StorageClassEntity;

public record StorageClassResponse(
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
        String serviceRegion,
        java.util.List<String> contentRegions,
        Integer priority
) {
    public static StorageClassResponse fromEntity(StorageClassEntity entity) {
        return new StorageClassResponse(
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
                entity.serviceRegion,
                entity.contentRegions,
                entity.priority
        );
    }
}
