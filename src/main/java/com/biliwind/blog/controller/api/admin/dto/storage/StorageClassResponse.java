package com.biliwind.blog.controller.api.admin.dto.storage;

import com.biliwind.blog.model.StorageClassEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
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
        java.util.List<String> excludedContentRegions,
        Boolean allowEncryptedBackup,
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
                "{}",
                entity.supportedTypes,
                entity.cdnDomain,
                entity.cdnEnabled,
                entity.serviceRegion,
                entity.contentRegions,
                entity.excludedContentRegions,
                entity.allowEncryptedBackup,
                entity.priority
        );
    }

    public static StorageClassResponse fromEntity(StorageClassEntity entity,
                                                  com.biliwind.blog.service.storage.StorageConfigProtector protector) {
        return new StorageClassResponse(
                entity.id,
                entity.name,
                entity.displayName,
                entity.providerType,
                entity.isEnabled,
                entity.isPrimary,
                entity.role,
                protector.maskForResponse(entity.configJson),
                entity.supportedTypes,
                entity.cdnDomain,
                entity.cdnEnabled,
                entity.serviceRegion,
                entity.contentRegions,
                entity.excludedContentRegions,
                entity.allowEncryptedBackup,
                entity.priority
        );
    }
}
