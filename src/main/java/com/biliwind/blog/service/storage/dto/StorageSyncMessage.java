package com.biliwind.blog.service.storage.dto;

public record StorageSyncMessage(
        Long mediaId,
        String storageClassName,
        String variantType,
        int retryCount
) {
}
