package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
            List<MediaReferenceItem> references) {
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
}
