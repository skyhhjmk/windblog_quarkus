package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiTopicPoolRun;
import com.biliwind.blog.model.AiTopicPoolSettings;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence and status transitions for asynchronous local topic discovery runs. */
@ApplicationScoped
public class AiTopicRunService {

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
        run.createdAt = OffsetDateTime.now();
        run.persist();
        run.flush();
        return view(run);
    }

    @Transactional
    public void markStarted(Long runId) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) return;
        run.status = "RUNNING";
        run.startedAt = OffsetDateTime.now();
        run.persist();
    }

    @Transactional
    public void complete(Long runId, int topicCount) {
        AiTopicPoolRun run = AiTopicPoolRun.findById(runId);
        if (run == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        run.status = "SUCCEEDED";
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
        run.status = "FAILED";
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

    @Transactional
    public Map<String, Object> activeRun() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime staleBefore = now.minusMinutes(30);
        List<AiTopicPoolRun> stale = AiTopicPoolRun.find(
                "status in ?1 and coalesce(startedAt, createdAt) < ?2",
                List.of("QUEUED", "RUNNING"), staleBefore).list();
        for (AiTopicPoolRun run : stale) {
            // A restart or crash can leave a run behind; do not let it block
            // every future manual run forever.
            run.status = "FAILED";
            run.errorMessage = "任务超时或服务重启";
            run.completedAt = now;
            run.persist();
        }
        AiTopicPoolRun run = AiTopicPoolRun.find(
                "status in ?1 order by id desc", List.of("QUEUED", "RUNNING")).firstResult();
        return run == null ? null : view(run);
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
