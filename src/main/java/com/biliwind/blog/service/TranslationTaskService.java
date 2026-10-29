package com.biliwind.blog.service;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationBatchView;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationTaskView;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.TranslationTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable submission and state transitions for asynchronous translation tasks. */
@ApplicationScoped
public class TranslationTaskService {

    private static final int MAX_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    @Inject
    EntityManager entityManager;

    @Inject
    AdminTranslationService translationService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Transactional
    public TranslationBatchView submit(List<Long> postIds, String targetLanguage,
                                       String sourceLanguage, boolean overwrite) {
        if (postIds == null || postIds.isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : postIds) {
            if (id != null && id > 0) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        if (uniqueIds.size() > MAX_BATCH_SIZE) {
            throw new BadRequestException("单次最多提交 " + MAX_BATCH_SIZE + " 篇文章");
        }
        String target = translationService.defaultTargetLanguage();
        if (targetLanguage != null && !targetLanguage.isBlank()) {
            target = targetLanguage.trim();
        }
        String batchId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        Long userId = adminRequestContext.getUserId();
        List<TranslationTask> created = new ArrayList<>();
        for (Long postId : uniqueIds) {
            Post post = Post.find("id = ?1 and deletedAt is null", postId).firstResult();
            if (post == null) {
                continue;
            }
            TranslationTask task = new TranslationTask();
            task.postId = postId;
            task.targetLanguage = target;
            task.sourceLanguage = sourceLanguage == null || sourceLanguage.isBlank()
                    ? null : sourceLanguage.trim();
            task.overwrite = overwrite;
            task.status = "QUEUED";
            task.attempts = 0;
            task.maxAttempts = MAX_ATTEMPTS;
            task.nextAttemptAt = now;
            task.batchId = batchId;
            task.createdBy = userId;
            task.createdAt = now;
            task.updatedAt = now;
            task.persist();
            created.add(task);
        }
        if (created.isEmpty()) {
            throw new BadRequestException("所选文章不存在或已删除");
        }
        return batchView(created);
    }

    @Transactional
    public TranslationBatchView batchView(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new BadRequestException("batchId 不能为空");
        }
        List<TranslationTask> tasks = TranslationTask.find(
                "batchId = ?1 order by id asc", batchId).list();
        return batchView(tasks);
    }

    @Transactional
    public TranslationTaskView view(Long id) {
        TranslationTask task = id == null ? null : TranslationTask.findById(id);
        if (task == null) {
            throw new NotFoundException("翻译任务不存在");
        }
        return viewOf(task);
    }

    @Transactional
    public int requeueStuck(java.time.Duration olderThan) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(olderThan);
        return entityManager.createNativeQuery(
                        "update translation_tasks set status = 'QUEUED', next_attempt_at = now(), "
                                + "message = coalesce(message, '任务执行超时，已重新排队'), updated_at = now() "
                                + "where status = 'RUNNING' and started_at < ?1")
                .setParameter(1, cutoff)
                .executeUpdate();
    }

    @Transactional
    public Long claimNext() {
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "update translation_tasks set status = 'RUNNING', started_at = now(), "
                                + "attempts = attempts + 1, updated_at = now() "
                                + "where id = (select id from translation_tasks "
                                + "where status = 'QUEUED' and (next_attempt_at is null or next_attempt_at <= now()) "
                                + "order by next_attempt_at nulls first, id limit 1 for update skip locked) "
                                + "returning id")
                .getResultList();
        return ids.isEmpty() ? null : ids.get(0).longValue();
    }

    @Transactional
    public TranslationTask find(Long id) {
        return id == null ? null : TranslationTask.findById(id);
    }

    @Transactional
    public void markResult(Long id, com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostResult result) {
        TranslationTask task = TranslationTask.findById(id);
        if (task == null) return;
        task.status = result.skipped() ? "SKIPPED" : "SUCCEEDED";
        task.message = result.message();
        task.revisionNumber = result.revisionNumber();
        task.published = result.published();
        task.sourceLanguage = result.sourceLanguage() == null
                ? task.sourceLanguage : result.sourceLanguage();
        task.finishedAt = OffsetDateTime.now();
        task.updatedAt = task.finishedAt;
        task.persist();
    }

    @Transactional
    public void markFailed(Long id, String message) {
        TranslationTask task = TranslationTask.findById(id);
        if (task == null) return;
        task.status = "FAILED";
        task.message = safe(message);
        task.finishedAt = OffsetDateTime.now();
        task.updatedAt = task.finishedAt;
        task.persist();
    }

    /** Requeue after a transient/rate-limit failure, or fail after max attempts. */
    @Transactional
    public void markRetry(Long id, String message, long retryAfterSeconds) {
        TranslationTask task = TranslationTask.findById(id);
        if (task == null) return;
        task.message = safe(message);
        task.updatedAt = OffsetDateTime.now();
        if (task.attempts >= task.maxAttempts) {
            task.status = "FAILED";
            task.finishedAt = task.updatedAt;
        } else {
            long delay = Math.max(5, Math.min(900,
                    retryAfterSeconds > 0 ? retryAfterSeconds : 15L * task.attempts));
            task.status = "QUEUED";
            task.nextAttemptAt = task.updatedAt.plusSeconds(delay);
        }
        task.persist();
    }

    private TranslationBatchView batchView(List<TranslationTask> tasks) {
        List<TranslationTaskView> items = tasks.stream().map(this::viewOf).toList();
        int queued = 0;
        int running = 0;
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        for (TranslationTask task : tasks) {
            switch (task.status) {
                case "QUEUED" -> queued++;
                case "RUNNING" -> running++;
                case "SUCCEEDED" -> succeeded++;
                case "SKIPPED" -> skipped++;
                case "FAILED", "CANCELED" -> failed++;
                default -> {
                }
            }
        }
        String batchId = tasks.isEmpty() ? null : tasks.get(0).batchId;
        return new TranslationBatchView(batchId, items, tasks.size(), queued, running,
                succeeded, skipped, failed);
    }

    private TranslationTaskView viewOf(TranslationTask task) {
        return new TranslationTaskView(task.id, task.postId, task.targetLanguage,
                task.sourceLanguage, task.overwrite, task.status, task.message,
                task.revisionNumber, task.published, task.attempts, task.batchId,
                task.createdAt, task.startedAt, task.finishedAt);
    }

    private String safe(String message) {
        String sanitized = SensitiveMessageSanitizer.sanitize(message);
        if (sanitized == null || sanitized.isBlank()) {
            return "翻译失败";
        }
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }
}
