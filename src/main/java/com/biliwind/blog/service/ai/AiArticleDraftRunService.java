package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiArticleDraftRun;
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

/** Persistence and status transitions for asynchronous article draft runs. */
@ApplicationScoped
public class AiArticleDraftRunService {

    @Inject
    Instance<AiArticleDraftRunService> self;

    @Inject
    AiRunLifecycle lifecycle;

    @Inject
    NodeRoleService nodeRoleService;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Map<String, Object> create(Long topicId, Long providerId, String language, String instructions,
                                      String repostPolicyCode, boolean regenerate, Long operatorId) {
        AiArticleDraftRun run = new AiArticleDraftRun();
        run.topicId = topicId;
        run.status = AiRunLifecycle.QUEUED;
        run.providerId = providerId;
        run.language = language;
        run.instructions = instructions;
        run.repostPolicyCode = repostPolicyCode;
        run.regenerate = regenerate;
        run.createdBy = operatorId;
        run.createdAt = OffsetDateTime.now();
        run.persist();
        run.flush();
        return view(run);
    }

    @Transactional
    public void markStarted(Long runId) {
        AiArticleDraftRun run = AiArticleDraftRun.findById(runId);
        if (run == null) return;
        run.status = AiRunLifecycle.RUNNING;
        run.startedAt = OffsetDateTime.now();
        run.persist();
    }

    @Transactional
    public void complete(Long runId, Long postId, String title, String summary) {
        AiArticleDraftRun run = AiArticleDraftRun.findById(runId);
        if (run == null) return;
        run.status = AiRunLifecycle.DRAFT_CREATED;
        run.postId = postId;
        run.title = truncate(title, 512);
        run.summary = summary;
        run.errorMessage = null;
        run.completedAt = OffsetDateTime.now();
        run.persist();
    }

    @Transactional
    public void fail(Long runId, String error) {
        AiArticleDraftRun run = AiArticleDraftRun.findById(runId);
        if (run == null) return;
        run.status = AiRunLifecycle.FAILED;
        run.errorMessage = error == null || error.isBlank() ? "未知错误" : truncate(error, 2000);
        run.completedAt = OffsetDateTime.now();
        run.persist();
    }

    /** Returns the active run for a topic, if any. */
    @Transactional
    public Map<String, Object> activeForTopic(Long topicId) {
        AiArticleDraftRun run = AiArticleDraftRun.find(
                "topicId = ?1 and status in ?2 order by id desc",
                topicId, lifecycle.activeStatuses()).firstResult();
        return run == null ? null : view(run);
    }

    @Transactional
    public Map<String, Object> view(Long runId) {
        AiArticleDraftRun run = AiArticleDraftRun.findById(runId);
        if (run == null) throw new IllegalArgumentException("draft run not found");
        return view(run);
    }

    @Transactional
    public Map<String, Object> list(Long topicId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        PanacheQuery<AiArticleDraftRun> query = topicId == null
                ? AiArticleDraftRun.find("order by createdAt desc, id desc")
                : AiArticleDraftRun.find("topicId = ?1 order by createdAt desc, id desc", topicId);
        long total = query.count();
        List<Map<String, Object>> items = query.page(Page.of(safePage - 1, safePageSize))
                .list().stream().map(this::view).toList();
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safePageSize);
    }

    @Transactional
    public int recoverStaleRuns() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AiArticleDraftRun> stale = AiArticleDraftRun.<AiArticleDraftRun>find(
                "status = ?1 or status = ?2", AiRunLifecycle.QUEUED, AiRunLifecycle.RUNNING)
                .page(0, 100).list();
        return lifecycle.recoverStale(stale, AiRunKind.ARTICLE_DRAFT, now).size();
    }

    @Scheduled(every = "1m", identity = "ai-article-draft-recovery")
    void scheduledRecovery() {
        if (!nodeRoleService.isPrimaryNode()) return;
        self.get().recoverStaleRuns();
    }

    void onStart(@Observes StartupEvent event) {
        if (!nodeRoleService.isPrimaryNode()) return;
        self.get().recoverStaleRuns();
    }

    private Map<String, Object> view(AiArticleDraftRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id);
        view.put("topicId", run.topicId);
        view.put("postId", run.postId);
        view.put("providerId", run.providerId);
        view.put("status", run.status);
        view.put("language", run.language);
        view.put("regenerate", run.regenerate);
        view.put("title", run.title == null ? "" : run.title);
        view.put("summary", run.summary == null ? "" : run.summary);
        view.put("error", run.errorMessage == null ? "" : run.errorMessage);
        view.put("createdBy", run.createdBy);
        view.put("createdAt", run.createdAt);
        view.put("startedAt", run.startedAt);
        view.put("completedAt", run.completedAt);
        return view;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
