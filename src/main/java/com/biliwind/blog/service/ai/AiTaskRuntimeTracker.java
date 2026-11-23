package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks live in-memory AI queue task executions (comment audits & post summaries),
 * supports cooperative/interruptive cancellation, and retains recent execution history
 * for comment diagnostics and queue monitoring.
 */
@ApplicationScoped
public class AiTaskRuntimeTracker {

    private static final int MAX_RECENT_EVENTS = 60;

    private final ConcurrentHashMap<Long, ActiveRun> activeAudits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ActiveRun> activeSummaries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveRun> runsById = new ConcurrentHashMap<>();
    private final Deque<RecentTaskEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public String startAudit(Long commentId, int retryCount, int maxRetries, Thread workerThread) {
        return startRun("ai-audit-tasks", "COMMENT", commentId, retryCount, maxRetries, workerThread, activeAudits);
    }

    public String startSummary(Long postId, int retryCount, int maxRetries, Thread workerThread) {
        return startRun("ai-summary-tasks", "POST", postId, retryCount, maxRetries, workerThread, activeSummaries);
    }

    private String startRun(String queueName, String targetType, Long targetId,
                            int retryCount, int maxRetries, Thread workerThread,
                            ConcurrentHashMap<Long, ActiveRun> targetMap) {
        String runId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        ActiveRun run = new ActiveRun(runId, queueName, targetType, targetId, retryCount, maxRetries, now, workerThread);
        run.stage = "DEQUEUED";
        run.stageLabel = "已从队列领取";
        run.stageDetail = retryCount > 0
                ? String.format("已从 %s 领取任务（第 %d/%d 次重试）", queueName, retryCount, maxRetries)
                : String.format("已从 %s 领取任务，准备执行", queueName);
        ActiveRun previous = targetMap.put(targetId, run);
        if (previous != null) {
            runsById.remove(previous.runId);
        }
        runsById.put(runId, run);
        return runId;
    }

    public void updateStage(String runId, String stage, String stageLabel, String detail) {
        if (runId == null) {
            return;
        }
        ActiveRun run = runsById.get(runId);
        if (run == null) {
            return;
        }
        run.stage = stage;
        run.stageLabel = stageLabel;
        run.stageDetail = detail;
        run.stageUpdatedAt = OffsetDateTime.now();
    }

    public void attachFuture(String runId, CompletableFuture<?> future) {
        if (runId == null || future == null) {
            return;
        }
        ActiveRun run = runsById.get(runId);
        if (run == null) {
            return;
        }
        run.activeFuture = future;
        if (run.aborted.get()) {
            future.cancel(true);
        }
    }

    public boolean isAborted(String runId) {
        if (runId == null) {
            return false;
        }
        ActiveRun run = runsById.get(runId);
        return run != null && run.aborted.get();
    }

    /**
     * Marks an active comment audit as aborted, cancels its in-flight future,
     * and interrupts the worker thread blocking on it.
     *
     * @return true if an active in-memory execution was found and signalled
     */
    public boolean abortAudit(Long commentId, String reason) {
        if (commentId == null) {
            return false;
        }
        ActiveRun run = activeAudits.get(commentId);
        if (run == null) {
            return false;
        }
        run.aborted.set(true);
        run.abortReason = reason == null || reason.isBlank() ? "已被管理员强制中断" : reason;
        run.stage = "ABORTING";
        run.stageLabel = "正在强制中断";
        run.stageDetail = run.abortReason;
        run.stageUpdatedAt = OffsetDateTime.now();

        CompletableFuture<?> future = run.activeFuture;
        if (future != null) {
            future.cancel(true);
        }
        Thread worker = run.workerThread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        return true;
    }

    public void finishAudit(String runId, String status, String detail, long durationMs, String error) {
        finishRun(runId, status, detail, durationMs, error, activeAudits);
    }

    public void finishSummary(String runId, String status, String detail, long durationMs, String error) {
        finishRun(runId, status, detail, durationMs, error, activeSummaries);
    }

    private void finishRun(String runId, String status, String detail, long durationMs, String error,
                           ConcurrentHashMap<Long, ActiveRun> targetMap) {
        if (runId == null) {
            return;
        }
        ActiveRun run = runsById.remove(runId);
        if (run == null) {
            return;
        }
        targetMap.remove(run.targetId, run);

        RecentTaskEvent event = new RecentTaskEvent(
                run.runId,
                run.queueName,
                run.targetType,
                run.targetId,
                status,
                detail,
                error,
                run.retryCount,
                run.maxRetries,
                durationMs,
                OffsetDateTime.now()
        );
        recentEvents.addFirst(event);
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.pollLast();
        }
    }

    public ActiveTaskSnapshot getActiveAudit(Long commentId) {
        if (commentId == null) {
            return null;
        }
        ActiveRun run = activeAudits.get(commentId);
        return run == null ? null : run.toSnapshot();
    }

    public Map<Long, ActiveTaskSnapshot> snapshotActiveAudits() {
        Map<Long, ActiveTaskSnapshot> result = new ConcurrentHashMap<>();
        for (Map.Entry<Long, ActiveRun> entry : activeAudits.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toSnapshot());
        }
        return result;
    }

    public int activeCountForQueue(String queueName) {
        if ("ai-audit-tasks".equals(queueName)) {
            return activeAudits.size();
        }
        if ("ai-summary-tasks".equals(queueName)) {
            return activeSummaries.size();
        }
        return 0;
    }

    public List<ActiveTaskSnapshot> listActiveTasks() {
        List<ActiveTaskSnapshot> list = new ArrayList<>(activeAudits.size() + activeSummaries.size());
        for (ActiveRun run : activeAudits.values()) {
            list.add(run.toSnapshot());
        }
        for (ActiveRun run : activeSummaries.values()) {
            list.add(run.toSnapshot());
        }
        list.sort(Comparator.comparing(ActiveTaskSnapshot::startedAt));
        return list;
    }

    public List<RecentTaskEvent> listRecentEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_RECENT_EVENTS));
        List<RecentTaskEvent> list = new ArrayList<>(safeLimit);
        for (RecentTaskEvent event : recentEvents) {
            list.add(event);
            if (list.size() >= safeLimit) {
                break;
            }
        }
        return list;
    }

    private static final class ActiveRun {
        final String runId;
        final String queueName;
        final String targetType;
        final Long targetId;
        final int retryCount;
        final int maxRetries;
        final OffsetDateTime startedAt;
        final Thread workerThread;
        final AtomicBoolean aborted = new AtomicBoolean(false);

        volatile String stage;
        volatile String stageLabel;
        volatile String stageDetail;
        volatile String abortReason;
        volatile OffsetDateTime stageUpdatedAt;
        volatile CompletableFuture<?> activeFuture;

        ActiveRun(String runId, String queueName, String targetType, Long targetId,
                  int retryCount, int maxRetries, OffsetDateTime startedAt, Thread workerThread) {
            this.runId = runId;
            this.queueName = queueName;
            this.targetType = targetType;
            this.targetId = targetId;
            this.retryCount = retryCount;
            this.maxRetries = maxRetries;
            this.startedAt = startedAt;
            this.stageUpdatedAt = startedAt;
            this.workerThread = workerThread;
        }

        ActiveTaskSnapshot toSnapshot() {
            OffsetDateTime now = OffsetDateTime.now();
            long elapsedSeconds = Math.max(0L, Duration.between(startedAt, now).getSeconds());
            return new ActiveTaskSnapshot(
                    runId,
                    queueName,
                    targetType,
                    targetId,
                    stage,
                    stageLabel,
                    stageDetail,
                    retryCount,
                    maxRetries,
                    elapsedSeconds,
                    aborted.get(),
                    startedAt,
                    stageUpdatedAt
            );
        }
    }

    public record ActiveTaskSnapshot(
            String runId,
            String queueName,
            String targetType,
            Long targetId,
            String stage,
            String stageLabel,
            String stageDetail,
            int retryCount,
            int maxRetries,
            long elapsedSeconds,
            boolean aborting,
            OffsetDateTime startedAt,
            OffsetDateTime stageUpdatedAt
    ) {
    }

    public record RecentTaskEvent(
            String runId,
            String queueName,
            String targetType,
            Long targetId,
            String status,
            String detail,
            String error,
            int retryCount,
            int maxRetries,
            long durationMs,
            OffsetDateTime finishedAt
    ) {
    }
}
