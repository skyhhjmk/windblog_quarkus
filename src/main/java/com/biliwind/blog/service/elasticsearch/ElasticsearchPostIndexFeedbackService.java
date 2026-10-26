package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class ElasticsearchPostIndexFeedbackService {

    private static final Logger log = Logger.getLogger(ElasticsearchPostIndexFeedbackService.class);
    private static final String POST_INDEX_ALIAS = "windblog-posts";

    @Inject
    ElasticsearchConnectionManager connectionManager;
    @Inject
    ElasticsearchPostSearchService postSearchService;
    @Inject
    ElasticsearchPostIndexRepository postIndexRepository;
    @Inject
    NodeRoleService nodeRoleService;

    @ConfigProperty(name = "elasticsearch.post-index.auto-sync-cooldown-seconds", defaultValue = "600")
    long autoSyncCooldownSeconds;

    private final AtomicBoolean synchronizationRunning = new AtomicBoolean(false);
    private volatile Instant lastAutoSyncAttempt;
    private volatile FeedbackSnapshot snapshot = new FeedbackSnapshot(
            "CHECKING", "IDLE", 0, 0, 0, 0, "", "", "");

    @Scheduled(every = "5m", delayed = "30s", identity = "windblog-post-index-feedback",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkAndRepairIndex() {
        if (!synchronizationRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcileAutomatically();
        } catch (Exception exception) {
            recordFailure(exception);
        } finally {
            synchronizationRunning.set(false);
        }
    }

    public Map<String, Object> getFeedback() {
        FeedbackSnapshot current = snapshot;
        Map<String, Object> result = new HashMap<>();
        result.put("status", current.status());
        result.put("syncState", synchronizationRunning.get() ? "SYNCING" : current.syncState());
        result.put("healthy", "HEALTHY".equals(current.status()));
        result.put("autoSyncEnabled", !nodeRoleService.isEdgeNode());
        result.put("expectedCount", current.expectedCount());
        result.put("indexedCount", current.indexedCount());
        result.put("missingCount", current.missingCount());
        result.put("staleCount", current.staleCount());
        result.put("lastCheckedAt", current.lastCheckedAt());
        result.put("lastSyncedAt", current.lastSyncedAt());
        result.put("lastError", current.lastError());
        return result;
    }

    public SyncResult synchronizeAll() throws Exception {
        beginSynchronization();
        try {
            postSearchService.ensurePostIndexReady();
            Set<Long> expected = currentPublishedIds();
            for (Long postId : expected) {
                postSearchService.indexPublicPostById(postId, POST_INDEX_ALIAS);
            }
            synchronizeDifference(expected, postSearchService.getIndexedPostIds(POST_INDEX_ALIAS), POST_INDEX_ALIAS);
            postSearchService.refreshPostIndex(POST_INDEX_ALIAS);
            return verifyAndStore("MANUAL");
        } catch (Exception exception) {
            recordFailure(exception);
            throw exception;
        } finally {
            synchronizationRunning.set(false);
        }
    }

    public SyncResult rebuildIndex() throws Exception {
        beginSynchronization();
        String replacementIndex = null;
        boolean promoted = false;
        try {
            postSearchService.ensurePostIndexReady();
            replacementIndex = postSearchService.createRebuildIndex();
            for (Long postId : currentPublishedIds()) {
                postSearchService.indexPublicPostById(postId, replacementIndex);
            }
            reconcileTargetIndex(replacementIndex);
            postSearchService.promoteRebuildIndex(replacementIndex);
            promoted = true;

            // Reindex current content after the alias switch because a post may
            // have changed while the replacement generation was being built.
            Set<Long> expected = currentPublishedIds();
            for (Long postId : expected) {
                postSearchService.indexPublicPostById(postId, POST_INDEX_ALIAS);
            }
            Set<Long> indexed = postSearchService.getIndexedPostIds(POST_INDEX_ALIAS);
            synchronizeDifference(expected, indexed, POST_INDEX_ALIAS);
            postSearchService.refreshPostIndex(POST_INDEX_ALIAS);
            return verifyAndStore("REBUILD");
        } catch (Exception exception) {
            recordFailure(exception);
            if (replacementIndex != null && !promoted) {
                try {
                    promoted = postSearchService.isPostIndexAliasTarget(replacementIndex);
                    if (!promoted) {
                        postSearchService.deleteIndexGeneration(replacementIndex);
                    }
                } catch (Exception aliasCheckException) {
                    exception.addSuppressed(aliasCheckException);
                    log.warnf("Could not verify whether replacement index %s was promoted; preserving it",
                            replacementIndex);
                }
            }
            throw exception;
        } finally {
            synchronizationRunning.set(false);
        }
    }

    private void reconcileAutomatically() throws Exception {
        if (nodeRoleService.isEdgeNode()) {
            snapshot = new FeedbackSnapshot("DISABLED", "IDLE", 0, 0, 0, 0,
                    Instant.now().toString(), snapshot.lastSyncedAt(), "");
            return;
        }
        if (!connectionManager.isAvailable()) {
            long expected = currentPublishedIds().size();
            snapshot = new FeedbackSnapshot("UNAVAILABLE", "WAITING", expected,
                    snapshot.indexedCount(), snapshot.missingCount(), snapshot.staleCount(),
                    Instant.now().toString(), snapshot.lastSyncedAt(), "Elasticsearch connection is unavailable");
            return;
        }

        postSearchService.ensurePostIndexReady();
        Set<Long> expected = currentPublishedIds();
        Set<Long> indexed = postSearchService.getIndexedPostIds(POST_INDEX_ALIAS);
        Difference difference = difference(expected, indexed);
        if (difference.missing().isEmpty() && difference.stale().isEmpty()) {
            snapshot = healthySnapshot(expected.size(), indexed.size(), snapshot.lastSyncedAt());
            return;
        }

        snapshot = new FeedbackSnapshot("OUT_OF_SYNC", "WAITING", expected.size(), indexed.size(),
                difference.missing().size(), difference.stale().size(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
        if (autoSyncCooldownActive()) {
            return;
        }

        lastAutoSyncAttempt = Instant.now();
        snapshot = new FeedbackSnapshot("OUT_OF_SYNC", "SYNCING", expected.size(), indexed.size(),
                difference.missing().size(), difference.stale().size(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
        log.warnf("Elasticsearch article index is out of sync; adding %d missing and removing %d stale documents",
                difference.missing().size(), difference.stale().size());
        synchronizeDifference(expected, indexed, POST_INDEX_ALIAS);
        postSearchService.refreshPostIndex(POST_INDEX_ALIAS);
        verifyAndStore("AUTO");
    }

    private void reconcileTargetIndex(String targetIndex) throws Exception {
        postSearchService.refreshPostIndex(targetIndex);
        Set<Long> expected = currentPublishedIds();
        Set<Long> indexed = postSearchService.getIndexedPostIds(targetIndex);
        synchronizeDifference(expected, indexed, targetIndex);
        postSearchService.refreshPostIndex(targetIndex);
        Difference difference = difference(currentPublishedIds(),
                postSearchService.getIndexedPostIds(targetIndex));
        if (!difference.missing().isEmpty() || !difference.stale().isEmpty()) {
            throw new IllegalStateException("Replacement Elasticsearch index did not match published article data");
        }
    }

    private SyncResult verifyAndStore(String mode) throws Exception {
        Set<Long> expected = currentPublishedIds();
        Set<Long> indexed = postSearchService.getIndexedPostIds(POST_INDEX_ALIAS);
        Difference difference = difference(expected, indexed);
        if (!difference.missing().isEmpty() || !difference.stale().isEmpty()) {
            snapshot = new FeedbackSnapshot("OUT_OF_SYNC", "FAILED", expected.size(), indexed.size(),
                    difference.missing().size(), difference.stale().size(), Instant.now().toString(),
                    snapshot.lastSyncedAt(), "Index verification did not match the published article set");
            throw new IllegalStateException("Elasticsearch article index verification failed");
        }
        String syncedAt = Instant.now().toString();
        snapshot = healthySnapshot(expected.size(), indexed.size(), syncedAt);
        log.infof("Elasticsearch article index %s completed; verified %d documents", mode.toLowerCase(), indexed.size());
        return new SyncResult(expected.size(), indexed.size(), mode);
    }

    private void synchronizeDifference(Set<Long> expected, Set<Long> indexed, String targetIndex) throws Exception {
        Difference difference = difference(expected, indexed);
        for (Long postId : difference.missing()) {
            postSearchService.indexPublicPostById(postId, targetIndex);
        }
        for (Long postId : difference.stale()) {
            postSearchService.deletePostIndex(postId, targetIndex);
        }
    }

    private Difference difference(Set<Long> expected, Set<Long> indexed) {
        Set<Long> missing = new LinkedHashSet<>(expected);
        missing.removeAll(indexed);
        Set<Long> stale = new LinkedHashSet<>(indexed);
        stale.removeAll(expected);
        return new Difference(missing, stale);
    }

    private Set<Long> currentPublishedIds() {
        return new LinkedHashSet<>(postIndexRepository.listPublicPublishedPostIds());
    }

    private FeedbackSnapshot healthySnapshot(long expected, long indexed, String syncedAt) {
        return new FeedbackSnapshot("HEALTHY", "IDLE", expected, indexed, 0, 0,
                Instant.now().toString(), syncedAt, "");
    }

    private boolean autoSyncCooldownActive() {
        Instant lastAttempt = lastAutoSyncAttempt;
        return lastAttempt != null && Duration.between(lastAttempt, Instant.now()).getSeconds()
                < Math.max(0, autoSyncCooldownSeconds);
    }

    private void beginSynchronization() {
        if (!synchronizationRunning.compareAndSet(false, true)) {
            throw new SyncAlreadyRunningException("Elasticsearch article index synchronization is already running");
        }
        snapshot = new FeedbackSnapshot("SYNCING", "SYNCING", snapshot.expectedCount(), snapshot.indexedCount(),
                snapshot.missingCount(), snapshot.staleCount(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
    }

    private void recordFailure(Exception exception) {
        FeedbackSnapshot current = snapshot;
        String message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        snapshot = new FeedbackSnapshot("ERROR", "FAILED", current.expectedCount(), current.indexedCount(),
                current.missingCount(), current.staleCount(), Instant.now().toString(),
                current.lastSyncedAt(), message);
        log.error("Elasticsearch article index feedback or synchronization failed", exception);
    }

    public static class SyncAlreadyRunningException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SyncAlreadyRunningException(String message) {
            super(message);
        }
    }

    public record SyncResult(long expectedCount, long indexedCount, String mode) {
    }

    private record Difference(Set<Long> missing, Set<Long> stale) {
    }

    private record FeedbackSnapshot(String status, String syncState, long expectedCount, long indexedCount,
                                    long missingCount, long staleCount, String lastCheckedAt,
                                    String lastSyncedAt, String lastError) {
    }
}
