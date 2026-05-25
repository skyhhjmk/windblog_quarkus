package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AdminMediaDtos {

    private AdminMediaDtos() {
    }

    public record MediaReferenceItem(
            Long postId,
            String postSlug,
            String postTitle,
            short usageType,
            OffsetDateTime referencedAt) {
    }

    public record MediaItem(
            Long id,
            String storageKey,
            String url,
            String thumbnailUrl,
            String previewUrl,
            boolean requiresManualOriginal,
            String fileName,
            String mimeType,
            Long size,
            short mediaType,
            Integer width,
            Integer height,
            Long uploadedBy,
            String uploadedByName,
            OffsetDateTime createdAt,
            boolean referenced,
            List<MediaReferenceItem> references,
            List<String> visibilityRegions,
            List<String> hiddenRegions,
            List<String> syncStorageClasses,
            List<String> skipStorageClasses,
            Map<String, Object> storageClasses,
            Map<String, Object> metadata) {
    }

    public record MediaUpdateRequest(
            List<String> visibilityRegions,
            List<String> hiddenRegions,
            List<String> syncStorageClasses,
            List<String> skipStorageClasses) {
    }

    public record MediaListResult(
            List<MediaItem> items,
            long total,
            int page,
            int pageSize) {
    }

    public record MediaScanResult(
            long postsScanned,
            long referencesCreated,
            long unreferenced) {
    }

    public record BatchRetryResult(
            int totalCount,
            int successCount,
            int failedCount,
            List<BatchRetryItemResult> results) {
    }

    public record BatchRetryItemResult(
            Long mediaId,
            String fileName,
            boolean success,
            String errorMessage) {
    }
}
