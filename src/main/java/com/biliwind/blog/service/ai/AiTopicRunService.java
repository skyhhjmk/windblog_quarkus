package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiTopicPoolRun;
import com.biliwind.blog.model.AiTopicPoolSettings;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence and status transitions for asynchronous topic discovery runs. */
@ApplicationScoped
public class AiTopicRunService {

    @Inject
    Instance<AiTopicRunService> self;

    @Inject
    AiRunLifecycle lifecycle;

    @Inject
    NodeRoleService nodeRoleService;

    /**
     * Creates and commits the run before the asynchronous worker starts, so the
     * worker never races an uncommitted row.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<String, Object> create(Long providerId, int seedCount, String traceId) {
        AiTopicPoolRun run = new AiTopicPoolRun();
        run.providerId = providerId;
        run.seedCount = seedCount;
        run.traceId = traceId;
        run.status = AiRunLifecycle.QUEUED;
        run.createdAt = OffsetDateTime.now();
        run.persist();
        run.flush();
        return view(run);
    }

    @Transactional
    public void markStarted(Long runId) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) return;
        run.status = AiRunLifecycle.RUNNING;
        run.startedAt = OffsetDateTime.now();
        run.persist();
    }

    @Transactional
    public void complete(Long runId, int topicCount) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        run.status = AiRunLifecycle.SUCCEEDED;
        run.topicCount = topicCount;
        run.errorMessage = null;
        run.completedAt = now;
        run.persist();
        AiTopicPoolSettings settings = AiTopicPoolSettings.findById(1L);
        if (settings != null) {
            settings.lastRunAt = now;
            settings.lastSuccessAt = now;
            settings.lastError = null;
            settings.updatedAt = now;
            settings.persist();
        }
    }

    @Transactional
    public void fail(Long runId, String error) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        run.status = AiRunLifecycle.FAILED;
        run.errorMessage = error == null || error.isBlank() ? "未知错误" : truncate(error, 2000);
        run.completedAt = now;
        run.persist();
        AiTopicPoolSettings settings = AiTopicPoolSettings.findById(1L);
        if (settings != null) {
            settings.lastRunAt = now;
            settings.lastError = run.errorMessage;
            settings.updatedAt = now;
            settings.persist();
        }
    }

    /** Returns the active run, if any. Stale runs are handled by the recovery scheduler. */
    @Transactional
    public Map<String, Object> activeRun() {
        AiTopicPoolRun run = AiTopicPoolRun.find(
                "status in ?1 order by id desc", lifecycle.activeStatuses()).firstResult();
        return run == null ? null : view(run);
    }

    /**
     * Fails runs whose in-process worker died (deployment/restart) or whose
     * provider call never returned. Without this an orphaned run keeps the UI
     * polling forever and blocks every future manual run.
     */
    @Transactional
    public int recoverStaleRuns() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AiTopicPoolRun> stale = AiTopicPoolRun.<AiTopicPoolRun>find(
                "status = ?1 or status = ?2", AiRunLifecycle.QUEUED, AiRunLifecycle.RUNNING)
                .page(0, 100).list();
        List<AiTopicPoolRun> recovered = lifecycle.recoverStale(stale, AiRunKind.TOPIC_DISCOVERY, now);
        for (AiTopicPoolRun run : recovered) {
            AiTopicPoolSettings settings = AiTopicPoolSettings.findById(1L);
            if (settings != null) {
                settings.lastRunAt = now;
                settings.lastError = run.errorMessage;
                settings.updatedAt = now;
                settings.persist();
            }
        }
        return recovered.size();
    }

    @Scheduled(every = "1m", identity = "ai-topic-run-recovery")
    void scheduledRecovery() {
        if (!nodeRoleService.isPrimaryNode()) return;
        self.get().recoverStaleRuns();
    }

    void onStart(@Observes StartupEvent event) {
        if (!nodeRoleService.isPrimaryNode()) return;
        // A run can only be executed by the process that created it. Anything
        // left non-terminal at boot is therefore dead.
        self.get().recoverStaleRuns();
    }

    @Transactional
    public Map<String, Object> view(Long runId) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) throw new IllegalArgumentException("topic discovery run not found");
        return view(run);
    }

    @Transactional
    public Map<String, Object> list(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        PanacheQuery<AiTopicPoolRun> query = AiTopicPoolRun.find("order by createdAt desc, id desc");
        long total = query.count();
        List<Map<String, Object>> items = query.page(Page.of(safePage - 1, safePageSize))
                .list().stream().map(this::view).toList();
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safePageSize);
    }

    private Map<String, Object> view(AiTopicPoolRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id);
        view.put("trigger", run.triggerType);
        view.put("status", run.status);
        view.put("providerId", run.providerId);
        view.put("seedCount", run.seedCount);
        view.put("topicCount", run.topicCount);
        view.put("error", run.errorMessage == null ? "" : run.errorMessage);
        view.put("traceId", run.traceId);
        view.put("taskId", null);
        view.put("taskStatus", null);
        view.put("execution", Map.of("events", List.of(), "attempts", List.of(), "attemptCount", 0));
        view.put("createdAt", run.createdAt);
        view.put("startedAt", run.startedAt);
        view.put("completedAt", run.completedAt);
        return view;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
