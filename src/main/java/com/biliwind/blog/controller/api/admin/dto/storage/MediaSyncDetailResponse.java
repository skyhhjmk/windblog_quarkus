package com.biliwind.blog.controller.api.admin.dto.storage;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record MediaSyncDetailResponse(
        Long mediaId,
        String fileName,
        String mimeType,
        Map<String, Object> storageClasses
) {
}
