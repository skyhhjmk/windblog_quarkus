package com.biliwind.blog.service;

import com.biliwind.blog.service.elasticsearch.EsSyncTask;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;

/** Writes infrastructure retries to the same durable outbox used by normal dispatch. */
@ApplicationScoped
public class ReliableInfrastructureTaskService {

    @Inject
    OutboxEventService outboxEventService;

    public void enqueueStorageSync(StorageSyncMessage task) {
        enqueueStorageSync(task, Duration.ZERO);
    }

    public void enqueueStorageSync(StorageSyncMessage task, Duration delay) {
        if (task == null || task.mediaId() == null || isBlank(task.storageClassName())
                || isBlank(task.variantType())) {
            throw new IllegalArgumentException("存储同步任务字段不完整");
        }
        outboxEventService.enqueue(
                "STORAGE_REPLAY:" + task.mediaId() + ":" + task.storageClassName()
                        + ":" + task.variantType() + ":" + task.retryCount(),
                "STORAGE_SYNC",
                "MEDIA",
                String.valueOf(task.mediaId()),
                Map.of("mediaId", task.mediaId(),
                        "storageClassName", task.storageClassName(),
                        "variantType", task.variantType(),
                        "retryCount", task.retryCount()),
                null, delay);
    }

    public void enqueueEsSync(EsSyncTask task) {
        if (task == null || task.postId() == null || isBlank(task.actionType())) {
            throw new IllegalArgumentException("ES 同步任务字段不完整");
        }
        outboxEventService.enqueue(
                "ES_REPLAY:" + task.postId() + ":" + task.actionType(),
                "ES_SYNC",
                "POST",
                String.valueOf(task.postId()),
                Map.of("postId", task.postId(), "action", task.actionType()),
                null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
