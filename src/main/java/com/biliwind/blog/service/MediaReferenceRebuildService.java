package com.biliwind.blog.service;

import com.biliwind.blog.model.MediaReferenceRebuildJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;

@ApplicationScoped
public class MediaReferenceRebuildService {
    private static final int BATCH_SIZE = 50;

    @Inject
    Instance<OutboxEventService> outboxEventService;

    @Inject
    MediaManagementService mediaManagementService;

    @Transactional
    public MediaReferenceRebuildJob start() {
        MediaReferenceRebuildJob active = MediaReferenceRebuildJob.find(
                "status in ?1 order by id desc", java.util.List.of("PENDING", "RUNNING")).firstResult();
        if (active != null) {
            return active;
        }
        MediaReferenceRebuildJob job = new MediaReferenceRebuildJob();
        job.status = "PENDING";
        job.createdAt = OffsetDateTime.now();
        job.updatedAt = job.createdAt;
        job.persistAndFlush();
        outboxEventService.get().enqueue(
                "MEDIA_REFERENCE_REBUILD:" + job.id + ":0",
                "MEDIA_REFERENCE_REBUILD",
                "MEDIA_REFERENCE_REBUILD_JOB",
                job.id.toString(),
                java.util.Map.of("jobId", job.id),
                null);
        return job;
    }

    @Transactional
    public void runBatch(Long jobId) {
        MediaReferenceRebuildJob job = MediaReferenceRebuildJob.findById(jobId);
        if (job == null || "COMPLETED".equals(job.status)) {
            return;
        }
        if ("PENDING".equals(job.status)) {
            mediaManagementService.clearAllMediaReferences();
            job.status = "RUNNING";
        }

        MediaManagementService.ReferenceBatchResult batch =
                mediaManagementService.rebuildReferenceBatch(job.lastPostId, BATCH_SIZE);
        job.lastPostId = batch.lastPostId();
        job.postsScanned = job.postsScanned + batch.postsScanned();
        job.referencesCreated = job.referencesCreated + batch.referencesCreated();
        job.updatedAt = OffsetDateTime.now();
        job.lastError = null;

        if (batch.hasMore()) {
            outboxEventService.get().enqueue(
                    "MEDIA_REFERENCE_REBUILD:" + job.id + ":" + job.lastPostId,
                    "MEDIA_REFERENCE_REBUILD",
                    "MEDIA_REFERENCE_REBUILD_JOB",
                    job.id.toString(),
                    java.util.Map.of("jobId", job.id),
                    null);
        } else {
            job.status = "COMPLETED";
            job.unreferencedMedia = mediaManagementService.countUnreferencedMedia();
        }
    }

    @Transactional
    public MediaReferenceRebuildJob find(Long jobId) {
        return MediaReferenceRebuildJob.findById(jobId);
    }
}
