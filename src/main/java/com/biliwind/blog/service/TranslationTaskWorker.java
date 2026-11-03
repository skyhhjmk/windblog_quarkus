package com.biliwind.blog.service;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.common.exception.ConflictException;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostResult;
import com.biliwind.blog.model.TranslationTask;
import com.biliwind.blog.service.ai.AiConcurrencyLimitException;
import com.biliwind.blog.service.ai.AiRateLimitException;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drains the durable translation queue on the primary node. Tasks are claimed
 * with {@code FOR UPDATE SKIP LOCKED} and processed asynchronously up to the
 * configured parallel worker limit. AI provider concurrency and rate limits
 * govern actual provider utilization.
 */
@ApplicationScoped
public class TranslationTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TranslationTaskWorker.class);

    @Inject
    TranslationTaskService tasks;

    @Inject
    AdminTranslationService translationService;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "windblog.ai.translation.max-parallel", defaultValue = "5")
    int maxParallel;

    private final AtomicInteger activeTasks = new AtomicInteger(0);

    @Scheduled(every = "2s", identity = "translation-task-worker",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Blocking
    void tick() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        int availableSlots = Math.max(1, maxParallel) - activeTasks.get();
        for (int i = 0; i < availableSlots; i++) {
            Long id = tasks.claimNext();
            if (id == null) {
                break;
            }
            activeTasks.incrementAndGet();
            managedExecutor.runAsync(() -> {
                try {
                    executeTask(id);
                } finally {
                    activeTasks.decrementAndGet();
                }
            });
        }
    }

    void executeTask(Long id) {
        TranslationTask task = tasks.find(id);
        if (task == null) {
            return;
        }
        log.info("[翻译队列] 开始处理任务 id={}, postId={}, target={}, attempt={}/{}",
                id, task.postId, task.targetLanguage, task.attempts, task.maxAttempts);
        try {
            TranslatePostResult result = translationService.translatePost(
                    task.postId, task.targetLanguage, task.sourceLanguage, task.overwrite,
                    task.createdBy);
            tasks.markResult(id, result);
            log.info("[翻译队列] 任务完成 id={}, status={}, revision={}",
                    id, result.skipped() ? "SKIPPED" : "SUCCEEDED", result.revisionNumber());
        } catch (AiRateLimitException rateLimit) {
            tasks.markRetry(id, rateLimit.getMessage(), rateLimit.retryAfterSeconds());
            log.warn("[翻译队列] 提供商限流，任务将重试 id={}, retryAfter={}s",
                    id, rateLimit.retryAfterSeconds());
        } catch (AiConcurrencyLimitException concurrency) {
            tasks.markRetry(id, concurrency.getMessage(), 2);
            log.warn("[翻译队列] 提供商并发达上限，任务将重试 id={}, provider={}",
                    id, concurrency.getProviderName());
        } catch (BadRequestException | NotFoundException permanent) {
            tasks.markFailed(id, permanent.getMessage());
            log.warn("[翻译队列] 任务不可重试 id={}, error={}", id, safe(permanent.getMessage()));
        } catch (ConflictException conflict) {
            tasks.markRetry(id, conflict.getMessage(), 0);
            log.warn("[翻译队列] 文章已变化，任务重新排队 id={}", id);
        } catch (Exception exception) {
            tasks.markRetry(id, exception.getMessage(), 0);
            log.warn("[翻译队列] 任务失败，将重试 id={}, error={}", id, safe(exception.getMessage()));
        }
    }

    @Scheduled(every = "1m", identity = "translation-task-recovery",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Blocking
    void recoverStuckTasks() {
        if (nodeRoleService.isEdgeNode()) {
            return;
        }
        int recovered = tasks.requeueStuck(Duration.ofMinutes(10));
        if (recovered > 0) {
            log.warn("[翻译队列] 重新排队 {} 个超时任务", recovered);
        }
    }

    private String safe(String message) {
        String sanitized = SensitiveMessageSanitizer.sanitize(message);
        return sanitized == null || sanitized.isBlank() ? "unknown" : sanitized;
    }
}
