package com.biliwind.blog.controller.api.admin.dto.storage;

import java.util.ArrayList;

public record SyncStatusResponse(
        int totalMedia,
        int totalVariants,
        int syncedCount,
        int pendingCount,
        int failedCount,
        ArrayList<MediaSyncDetailResponse> details
) {
}
