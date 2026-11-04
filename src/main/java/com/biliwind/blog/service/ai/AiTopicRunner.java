package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.context.api.ManagedExecutorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs provider topic discovery off the HTTP request thread. The admin request
 * only creates a run, so a slow AI provider can no longer trip the public edge
 * timeout (HTTP 554); the UI polls the run until it succeeds or fails.
 */
@ApplicationScoped
public class AiTopicRunner {

    private static final Logger log = LoggerFactory.getLogger(AiTopicRunner.class);

    /**
     * 管理端请求本身处于事务中；清空事务上下文，避免异步任务复用请求线程
     * 已提交的事务（Panache 随后会报 ContextNotActiveException）。
     */
    @Inject
    @ManagedExecutorConfig(cleared = ThreadContext.TRANSACTION)
    ManagedExecutor executor;

    @Inject
    AiTopicDiscoveryService discoveryService;

    @Inject
    LocalAiTopicService localTopicService;

    @Inject
    AiTopicRunService runService;

    @Inject
    ObjectMapper objectMapper;

    public void start(Long runId, Long providerId, List<AiTopicDiscoveryService.Seed> seeds,
                      int maxTopics, String language, String actorId, String traceId) {
        try {
            executor.runAsync(() -> execute(runId, providerId, seeds, maxTopics, language, actorId, traceId))
                    .exceptionally(error -> {
                        runService.fail(runId, rootMessage(error));
                        return null;
                    });
        } catch (RejectedExecutionException exception) {
            // Deployments and shutdowns reject queued tasks; the run row was
            // already committed, so it must be failed explicitly or it would
            // stay QUEUED forever.
            log.warn("话题发现任务调度失败 runId={}", runId, exception);
            runService.fail(runId, "任务调度失败: " + rootMessage(exception));
        } catch (RuntimeException exception) {
            log.warn("话题发现任务启动失败 runId={}", runId, exception);
            runService.fail(runId, rootMessage(exception));
        }
    }

    private void execute(Long runId, Long providerId, List<AiTopicDiscoveryService.Seed> seeds,
                         int maxTopics, String language, String actorId, String traceId) {
        try {
            runService.markStarted(runId);
            List<Map<String, Object>> topics = discoveryService.discover(
                    providerId, seeds, maxTopics, language);
            localTopicService.dispatch("topics.import",
                    objectMapper.valueToTree(Map.of("topics", topics)), actorId, traceId);
            runService.complete(runId, topics.size());
        } catch (RuntimeException exception) {
            log.warn("话题发现任务失败 runId={}", runId, exception);
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
