package com.biliwind.blog.controller.api.admin.dto.storage;

import java.util.List;

public record SyncStatusResponse(
        int totalMedia,
        int totalVariants,
        int syncedCount,
        int pendingCount,
        int failedCount,
        List<MediaSyncDetailResponse> details,
        long totalDetails,
        int page,
        int size
) {
}
