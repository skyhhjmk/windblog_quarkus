package com.biliwind.blog.service.storage.dto;

public record StorageSyncMessage(
        Long mediaId,
        String providerName,
        String variantType,
        int retryCount
) {
}
