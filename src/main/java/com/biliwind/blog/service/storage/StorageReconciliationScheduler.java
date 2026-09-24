package com.biliwind.blog.service.storage;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class StorageReconciliationScheduler {
    private static final Logger LOG = Logger.getLogger(StorageReconciliationScheduler.class);
    private final AtomicLong cursor = new AtomicLong();
    private final AtomicLong verificationCursor = new AtomicLong();

    @Inject StorageService storageService;
    @Inject NodeRoleService nodeRoleService;

    @Scheduled(every = "1m", identity = "storage-replica-reconcile",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcile() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        List<Media> batch = Media.find("deletedAt is null and processingStatus = ?1 and id > ?2 order by id",
                        "COMPLETED", cursor.get())
                .page(io.quarkus.panache.common.Page.ofSize(50)).list();
        if (batch.isEmpty()) {
            cursor.set(0);
            return;
        }
        for (Media media : batch) {
            cursor.set(media.id);
            try {
                storageService.reconcileMedia(media.id);
            } catch (Exception exception) {
                LOG.warnf(exception, "Storage reconciliation failed for mediaId=%d", media.id);
            }
        }
    }

    @Scheduled(every = "5m", identity = "storage-replica-verify",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void verify() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        List<Media> batch = Media.find("deletedAt is null and processingStatus = ?1 and id > ?2 order by id",
                        "COMPLETED", verificationCursor.get())
                .page(io.quarkus.panache.common.Page.ofSize(5)).list();
        if (batch.isEmpty()) {
            verificationCursor.set(0);
            return;
        }
        for (Media media : batch) {
            verificationCursor.set(media.id);
            try {
                storageService.verifyMediaCopies(media.id);
            } catch (Exception exception) {
                LOG.warnf(exception, "Storage verification failed for mediaId=%d", media.id);
            }
        }
    }
}
