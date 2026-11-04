package com.biliwind.blog.service.ai;

import com.biliwind.blog.service.AiArticleDraftService;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.context.api.ManagedExecutorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;

/**
 * Executes AI-provider article drafting outside the HTTP request thread. The
 * run row is committed before scheduling, so a rejected or crashed task can
 * always be failed and surfaced to the admin UI.
 */
@ApplicationScoped
public class AiArticleDraftRunner {

    private static final Logger log = LoggerFactory.getLogger(AiArticleDraftRunner.class);

    /**
     * 管理端请求本身处于事务中；清空事务上下文，避免异步任务复用请求线程
     * 已提交的事务（Panache 随后会报 ContextNotActiveException）。
     */
    @Inject
    @ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION)
    ManagedExecutor executor;

    @Inject
    AiArticleDraftService draftService;

    @Inject
    AiArticleDraftRunService runService;

    public void start(Long runId, Long topicId, Long providerId, String language, String instructions,
                      String repostPolicyCode, boolean regenerate, Long operatorId) {
        try {
            executor.runAsync(() -> execute(runId, topicId, providerId, language, instructions,
                            repostPolicyCode, regenerate, operatorId))
                    .exceptionally(error -> {
                        runService.fail(runId, rootMessage(error));
                        return null;
                    });
        } catch (RejectedExecutionException exception) {
            log.warn("AI 草稿任务调度失败 runId={}", runId, exception);
            runService.fail(runId, "任务调度失败: " + rootMessage(exception));
        } catch (RuntimeException exception) {
            log.warn("AI 草稿任务启动失败 runId={}", runId, exception);
            runService.fail(runId, rootMessage(exception));
        }
    }

    private void execute(Long runId, Long topicId, Long providerId, String language, String instructions,
                         String repostPolicyCode, boolean regenerate, Long operatorId) {
        try {
            runService.markStarted(runId);
            JsonNode topic = draftService.readTopic(topicId, operatorId);
            AiArticleDraftService.DraftPayload payload =
                    draftService.generateContent(topic, providerId, language, instructions);
            AiArticleDraftService.DraftSaved saved = draftService.persist(topicId, topic, payload,
                    repostPolicyCode, regenerate, operatorId);
            runService.complete(runId, saved.postId(), saved.title(), saved.summary());
        } catch (RuntimeException exception) {
            log.warn("AI 草稿任务失败 runId={}", runId, exception);
            runService.fail(runId, rootMessage(exception));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) current = current.getCause();
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
