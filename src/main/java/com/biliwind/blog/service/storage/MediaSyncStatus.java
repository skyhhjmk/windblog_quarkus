package com.biliwind.blog.service.storage;

import java.util.Map;

public class MediaSyncStatus {
    public Long mediaId;
    public int totalVariants;
    public int syncedCount;
    public int pendingCount;
    public int failedCount;
    public Map<String, Map<String, String>> details;
}
