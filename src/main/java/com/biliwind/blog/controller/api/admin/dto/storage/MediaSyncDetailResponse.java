package com.biliwind.blog.controller.api.admin.dto.storage;

import java.util.Map;

public record MediaSyncDetailResponse(
        Long mediaId,
        String fileName,
        String mimeType,
        Map<String, Object> storageClasses
) {
}
