package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the Elasticsearch article index aligned with PostgreSQL.
 * <p>
 * The article index is a disposable search cache, never the source of truth.
 * A scheduled audit compares the stored schema marker, document count, newest
 * indexed timestamp and (periodically) the full id set with PostgreSQL. Small
 * drifts are repaired in place; schema changes or large drifts rebuild a new
 * index generation and atomically move the write alias. A PostgreSQL advisory
 * lock keeps multiple primary instances from rebuilding at the same time.
 */
@ApplicationScoped
public class ElasticsearchPostIndexFeedbackService {

    private static final Logger log = Logger.getLogger(ElasticsearchPostIndexFeedbackService.class);
    private static final String POST_INDEX_ALIAS = "windblog-posts";
    private static final long CLUSTER_LOCK_KEY = 145002L;

    @Inject
    ElasticsearchConnectionManager connectionManager;
    @Inject
    ElasticsearchPostSearchService postSearchService;
    @Inject
    ElasticsearchPostIndexRepository postIndexRepository;
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    AgroalDataSource dataSource;

    @ConfigProperty(name = "elasticsearch.post-index.auto-sync-cooldown-seconds", defaultValue = "600")
    long autoSyncCooldownSeconds;

    @ConfigProperty(name = "elasticsearch.post-index.auto-rebuild-cooldown-seconds", defaultValue = "1800")
    long autoRebuildCooldownSeconds;

    @ConfigProperty(name = "elasticsearch.post-index.full-audit-interval-checks", defaultValue = "6")
    int fullAuditIntervalChecks;

    @ConfigProperty(name = "elasticsearch.post-index.rebuild-ratio-threshold", defaultValue = "0.2")
    double rebuildRatioThreshold;

    @ConfigProperty(name = "elasticsearch.post-index.rebuild-min-drift", defaultValue = "20")
    int rebuildMinDrift;

    @ConfigProperty(name = "elasticsearch.post-index.content-freshness-tolerance-seconds", defaultValue = "60")
    long contentFreshnessToleranceSeconds;

    private final AtomicBoolean synchronizationRunning = new AtomicBoolean(false);
    private final AtomicInteger auditCounter = new AtomicInteger();
    private volatile Instant lastAutoSyncAttempt;
    private volatile Instant lastAutoRebuildAttempt;
    private volatile int observedIndexVersion = -1;
    private volatile String indexedUpdatedAt = "";
    private volatile String databaseUpdatedAt = "";
    private volatile String lastAction = "IDLE";
    private volatile String lastRebuildAt = "";
    private volatile FeedbackSnapshot snapshot = new FeedbackSnapshot(
            "CHECKING", "IDLE", 0, 0, 0, 0, "", "", "");

    @Scheduled(every = "5m", delayed = "30s", identity = "windblog-post-index-feedback",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkAndRepairIndex() {
        if (nodeRoleService.isEdgeNode()) {
            snapshot = new FeedbackSnapshot("DISABLED", "IDLE", 0, 0, 0, 0,
                    Instant.now().toString(), snapshot.lastSyncedAt(), "");
            return;
        }
        if (!synchronizationRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            withClusterLock(() -> {
                reconcileAutomatically();
                return null;
            });
        } catch (SyncAlreadyRunningException exception) {
            log.debugf("Elasticsearch article index audit skipped: %s", exception.getMessage());
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
        result.put("autoRebuildEnabled", !nodeRoleService.isEdgeNode());
        result.put("expectedCount", current.expectedCount());
        result.put("indexedCount", current.indexedCount());
        result.put("missingCount", current.missingCount());
        result.put("staleCount", current.staleCount());
        result.put("schemaVersion", observedIndexVersion);
        result.put("expectedSchemaVersion", ElasticsearchPostSearchService.POST_INDEX_VERSION);
        result.put("indexedUpdatedAt", indexedUpdatedAt);
        result.put("databaseUpdatedAt", databaseUpdatedAt);
        result.put("lastAction", lastAction);
        result.put("lastRebuildAt", lastRebuildAt);
        result.put("lastCheckedAt", current.lastCheckedAt());
        result.put("lastSyncedAt", current.lastSyncedAt());
        result.put("lastError", current.lastError());
        return result;
    }

    public SyncResult synchronizeAll() throws Exception {
        if (nodeRoleService.isEdgeNode()) {
            throw new SyncAlreadyRunningException("边缘节点不维护文章索引");
        }
        return withClusterLock(this::synchronizeAllInternal);
    }

    private SyncResult synchronizeAllInternal() throws Exception {
        beginSynchronization();
        try {
            return synchronizeAllCore();
        } catch (Exception exception) {
            recordFailure(exception);
            throw exception;
        } finally {
            synchronizationRunning.set(false);
        }
    }

    private SyncResult synchronizeAllCore() throws Exception {
        postSearchService.ensurePostIndexReady();
        Set<Long> expected = currentPublishedIds();
        for (Long postId : expected) {
            postSearchService.indexPublicPostById(postId, POST_INDEX_ALIAS);
        }
        synchronizeDifference(expected, postSearchService.getIndexedPostIds(POST_INDEX_ALIAS), POST_INDEX_ALIAS);
        postSearchService.refreshPostIndex(POST_INDEX_ALIAS);
        lastAction = "MANUAL_SYNC";
        verifyContentFreshness(expected.size());
        return verifyAndStore("MANUAL");
    }

    public SyncResult rebuildIndex() throws Exception {
        if (nodeRoleService.isEdgeNode()) {
            throw new SyncAlreadyRunningException("边缘节点不维护文章索引");
        }
        return withClusterLock(this::rebuildIndexInternal);
    }

    private SyncResult rebuildIndexInternal() throws Exception {
        beginSynchronization();
        try {
            return rebuildIndexCore();
        } catch (Exception exception) {
            recordFailure(exception);
            throw exception;
        } finally {
            synchronizationRunning.set(false);
        }
    }

    /**
     * Runs without taking the local or cluster lock; callers must already hold
     * both. Used by the scheduled audit and the manual rebuild wrapper.
     */
    private SyncResult rebuildIndexCore() throws Exception {
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
            verifyContentFreshness(expected.size());
            SyncResult result = verifyAndStore("REBUILD");
            postSearchService.storeIndexVersion(ElasticsearchPostSearchService.POST_INDEX_VERSION);
            postSearchService.deleteSupersededPostIndexGenerations(replacementIndex);
            observedIndexVersion = ElasticsearchPostSearchService.POST_INDEX_VERSION;
            lastRebuildAt = Instant.now().toString();
            lastAction = "REBUILD";
            return result;
        } catch (Exception exception) {
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
        }
    }

    private void reconcileAutomatically() throws Exception {
        if (!connectionManager.isAvailable()) {
            long expected = postIndexRepository.countPublicPublishedPosts();
            snapshot = new FeedbackSnapshot("UNAVAILABLE", "WAITING", expected,
                    snapshot.indexedCount(), snapshot.missingCount(), snapshot.staleCount(),
                    Instant.now().toString(), snapshot.lastSyncedAt(),
                    "Elasticsearch connection is unavailable");
            lastAction = "ES_UNAVAILABLE";
            return;
        }

        postSearchService.ensurePostIndexReady();
        int storedVersion = postSearchService.getStoredIndexVersion();
        observedIndexVersion = storedVersion;

        long expectedCount = postIndexRepository.countPublicPublishedPosts();
        long indexedCount = postSearchService.getPostIndexCount(POST_INDEX_ALIAS);
        OffsetDateTime databaseMax = postIndexRepository.maxPublicPublishedUpdatedAt();
        OffsetDateTime indexMax = postSearchService.getMaxIndexedUpdatedAt(POST_INDEX_ALIAS);
        databaseUpdatedAt = formatInstant(databaseMax);
        indexedUpdatedAt = formatInstant(indexMax);

        if (storedVersion != ElasticsearchPostSearchService.POST_INDEX_VERSION) {
            snapshot = new FeedbackSnapshot("SCHEMA_OUTDATED", "WAITING", expectedCount, indexedCount,
                    0, 0, Instant.now().toString(), snapshot.lastSyncedAt(),
                    "索引结构版本 " + storedVersion + "，当前需要 "
                            + ElasticsearchPostSearchService.POST_INDEX_VERSION);
            lastAction = "SCHEMA_OUTDATED";
            maybeAutoRebuild();
            return;
        }

        boolean countsMatch = expectedCount == indexedCount;
        boolean contentFresh = contentFresh(databaseMax, indexMax, expectedCount);
        boolean auditDue = fullAuditDue();
        if (countsMatch && contentFresh && !auditDue) {
            lastAction = "HEALTHY";
            snapshot = healthySnapshot(expectedCount, indexedCount, snapshot.lastSyncedAt());
            return;
        }

        Set<Long> expected = currentPublishedIds();
        Set<Long> indexed = postSearchService.getIndexedPostIds(POST_INDEX_ALIAS);
        Difference difference = difference(expected, indexed);
        List<Long> changed = changedDocuments(databaseMax, indexMax, expected, contentFresh);
        long drift = difference.missing().size() + difference.stale().size();
        long threshold = rebuildThreshold(expected.size());

        snapshot = new FeedbackSnapshot("OUT_OF_SYNC", "WAITING", expected.size(), indexed.size(),
                difference.missing().size(), difference.stale().size(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
        if (drift >= threshold || changed.size() >= threshold) {
            lastAction = "REBUILD_REQUIRED";
            log.warnf("Elasticsearch article index drift is large (ids=%d, changed=%d, threshold=%d); rebuilding",
                    drift, changed.size(), threshold);
            maybeAutoRebuild();
            return;
        }

        if (drift == 0 && changed.isEmpty()) {
            if (!contentFresh) {
                // Same ids, but the newest indexed timestamp is older than the
                // database. Rebuild rather than reporting a healthy cache.
                lastAction = "CONTENT_STALE";
                maybeAutoRebuild();
                return;
            }
            lastAction = "HEALTHY";
            snapshot = healthySnapshot(expected.size(), indexed.size(), snapshot.lastSyncedAt());
            return;
        }

        if (autoSyncCooldownActive()) {
            lastAction = "SYNC_COOLDOWN";
            return;
        }

        lastAutoSyncAttempt = Instant.now();
        snapshot = new FeedbackSnapshot("OUT_OF_SYNC", "SYNCING", expected.size(), indexed.size(),
                difference.missing().size(), difference.stale().size(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
        lastAction = "AUTO_SYNC";
        log.warnf("Elasticsearch article index is out of sync; reindexing %d changed, adding %d missing "
                        + "and removing %d stale documents",
                changed.size(), difference.missing().size(), difference.stale().size());
        for (Long postId : changed) {
            postSearchService.indexPublicPostById(postId, POST_INDEX_ALIAS);
        }
        synchronizeDifference(expected, indexed, POST_INDEX_ALIAS);
        postSearchService.refreshPostIndex(POST_INDEX_ALIAS);
        indexedUpdatedAt = formatInstant(postSearchService.getMaxIndexedUpdatedAt(POST_INDEX_ALIAS));
        verifyContentFreshness(expected.size());
        verifyAndStore("AUTO");
    }

    private List<Long> changedDocuments(OffsetDateTime databaseMax, OffsetDateTime indexMax,
                                        Set<Long> expected, boolean contentFresh) {
        if (contentFresh) {
            return List.of();
        }
        if (indexMax == null) {
            return new ArrayList<>(expected);
        }
        OffsetDateTime threshold = indexMax.minusSeconds(Math.max(0, contentFreshnessToleranceSeconds));
        List<Long> changed = new ArrayList<>(postIndexRepository.listPublicPublishedIdsUpdatedAfter(threshold));
        changed.retainAll(expected);
        return changed;
    }

    private void maybeAutoRebuild() throws Exception {
        if (autoRebuildCooldownActive()) {
            lastAction = "REBUILD_COOLDOWN";
            return;
        }
        lastAutoRebuildAttempt = Instant.now();
        snapshot = new FeedbackSnapshot("REBUILDING", "SYNCING", snapshot.expectedCount(),
                snapshot.indexedCount(), snapshot.missingCount(), snapshot.staleCount(),
                Instant.now().toString(), snapshot.lastSyncedAt(), "");
        lastAction = "AUTO_REBUILD";
        log.warn("Rebuilding the Elasticsearch article search cache");
        rebuildIndexCore();
        lastAction = "AUTO_REBUILD";
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
        log.infof("Elasticsearch article index %s completed; verified %d documents",
                mode.toLowerCase(), indexed.size());
        return new SyncResult(expected.size(), indexed.size(), mode);
    }

    private void verifyContentFreshness(long expectedCount) throws Exception {
        OffsetDateTime databaseMax = postIndexRepository.maxPublicPublishedUpdatedAt();
        OffsetDateTime indexMax = postSearchService.getMaxIndexedUpdatedAt(POST_INDEX_ALIAS);
        databaseUpdatedAt = formatInstant(databaseMax);
        indexedUpdatedAt = formatInstant(indexMax);
        if (!contentFresh(databaseMax, indexMax, expectedCount)) {
            throw new IllegalStateException("Elasticsearch article index still contains stale content");
        }
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

    private boolean fullAuditDue() {
        int interval = Math.max(1, fullAuditIntervalChecks);
        return auditCounter.incrementAndGet() % interval == 0;
    }

    private boolean contentFresh(OffsetDateTime databaseMax, OffsetDateTime indexMax, long expectedCount) {
        return isContentFresh(databaseMax, indexMax, expectedCount, contentFreshnessToleranceSeconds);
    }

    static boolean isContentFresh(OffsetDateTime databaseMax, OffsetDateTime indexMax,
                                  long expectedCount, long toleranceSeconds) {
        if (expectedCount == 0 || databaseMax == null) {
            return true;
        }
        if (indexMax == null) {
            return false;
        }
        return !databaseMax.isAfter(indexMax.plusSeconds(Math.max(0, toleranceSeconds)));
    }

    private long rebuildThreshold(long expectedCount) {
        return calculateRebuildThreshold(expectedCount, rebuildMinDrift, rebuildRatioThreshold);
    }

    static long calculateRebuildThreshold(long expectedCount, int minDrift, double ratioThreshold) {
        long ratioDrift = (long) Math.ceil(expectedCount
                * Math.max(0, Math.min(1, ratioThreshold)));
        return Math.max(1, Math.max(minDrift, ratioDrift));
    }

    private String formatInstant(OffsetDateTime value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private boolean autoSyncCooldownActive() {
        Instant lastAttempt = lastAutoSyncAttempt;
        return lastAttempt != null && Duration.between(lastAttempt, Instant.now()).getSeconds()
                < Math.max(0, autoSyncCooldownSeconds);
    }

    private boolean autoRebuildCooldownActive() {
        Instant lastAttempt = lastAutoRebuildAttempt;
        return lastAttempt != null && Duration.between(lastAttempt, Instant.now()).getSeconds()
                < Math.max(0, autoRebuildCooldownSeconds);
    }

    private void beginSynchronization() {
        if (!synchronizationRunning.compareAndSet(false, true)) {
            throw new SyncAlreadyRunningException("Elasticsearch article index synchronization is already running");
        }
        snapshot = new FeedbackSnapshot("SYNCING", "SYNCING", snapshot.expectedCount(), snapshot.indexedCount(),
                snapshot.missingCount(), snapshot.staleCount(), Instant.now().toString(),
                snapshot.lastSyncedAt(), "");
    }

    private <T> T withClusterLock(Callable<T> action) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            boolean acquired;
            try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                lock.setLong(1, CLUSTER_LOCK_KEY);
                try (ResultSet resultSet = lock.executeQuery()) {
                    acquired = resultSet.next() && resultSet.getBoolean(1);
                }
            }
            if (!acquired) {
                throw new SyncAlreadyRunningException(
                        "Elasticsearch article index synchronization is already running on another node");
            }
            try {
                return action.call();
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    unlock.setLong(1, CLUSTER_LOCK_KEY);
                    unlock.execute();
                } catch (SQLException unlockFailure) {
                    log.warnf("Could not release Elasticsearch index advisory lock: %s",
                            SensitiveMessageSanitizer.sanitize(unlockFailure.getMessage()));
                }
            }
        }
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
        lastAction = "ERROR";
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
