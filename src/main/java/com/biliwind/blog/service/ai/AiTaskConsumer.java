package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.Post;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AiTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskConsumer.class);
    private static final String MQ_TAG = "[MQ]";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long TASK_TIMEOUT_SECONDS = 60;

    @Inject
    AiTaskProducer taskProducer;

    @Inject
    AiManager aiManager;

    private volatile int maxRetries;

    @PostConstruct
    void init() {
        log.info("{} AI Task Consumer 初始化完成", MQ_TAG);
    }

    private int getMaxRetries() {
        if (maxRetries == 0) {
            String envValue = System.getenv("AI_SUMMARY_MAX_RETRIES");
            if (envValue != null && !envValue.isBlank()) {
                try {
                    maxRetries = Integer.parseInt(envValue.trim());
                } catch (NumberFormatException e) {
                    maxRetries = DEFAULT_MAX_RETRIES;
                }
            } else {
                maxRetries = DEFAULT_MAX_RETRIES;
            }
        }
        return maxRetries;
    }

    /**
     * 消费 AI 摘要任务消息。
     *
     * @Blocking 注解确保此方法在独立的工作线程（Worker Pool）上执行，
     * 而非 Vert.x 事件循环线程，允许进行数据库等阻塞调用。
     * 接受 Message<T> 时，SmallRye 要求返回 CompletionStage<Void>。
     * 我们在 @Blocking 工作线程中用 .get() 同步等待，然后返回已完成的 stage。
     */
    @Incoming("ai-summary-tasks-in")
    @Blocking
    public java.util.concurrent.CompletionStage<Void> consume(Message<JsonObject> message) {
        log.info("{} 收到消息", MQ_TAG);

        AiSummaryTask task;
        try {
            task = message.getPayload().mapTo(AiSummaryTask.class);
        } catch (Exception deserializeEx) {
            log.error("{} 反序列化消息失败: {}", MQ_TAG, deserializeEx.getMessage());
            return safeAck(message);
        }

        if (task.postId() == null || task.content() == null || task.content().isEmpty()) {
            log.error("{} 任务字段无效，丢弃消息，postId={}", MQ_TAG, task.postId());
            return safeAck(message);
        }

        int currentRetryCount = getRetryCount(message, task);
        int allowedMaxRetries = getMaxRetries();
        log.info("{} 处理任务，postId={}, priority={}, retry={}/{}", MQ_TAG,
                task.postId(), task.priority(), currentRetryCount, allowedMaxRetries);

        long startTime = System.currentTimeMillis();
        try {
            // 阻塞等待 AI 结果（在 @Blocking 工作线程上安全）
            AiResult aiResult = aiManager.summarize(task.content())
                    .toCompletableFuture()
                    .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;

            // 在新事务中保存结果和审计日志（Worker 线程可以使用 Panache 阻塞 API）
            saveAiSummaryAndAuditLog(task.postId(), aiResult, durationMs, task.performedBy());
            log.info("{} AI 处理成功，postId={}，耗时={}ms", MQ_TAG, task.postId(), durationMs);
            return safeAck(message);

        } catch (Exception ex) {
            log.error("{} AI 处理失败，postId={}, error={}", MQ_TAG, task.postId(), ex.getMessage());
            return handleRetryOrDeadLetter(message, task, currentRetryCount, allowedMaxRetries, ex);
        }
    }

    private int getRetryCount(Message<JsonObject> message, AiSummaryTask task) {
        Optional<IncomingRabbitMQMetadata> metadata = message.getMetadata(IncomingRabbitMQMetadata.class);
        if (metadata.isPresent()) {
            Map<String, Object> headerMap = metadata.get().getHeaders();
            if (headerMap != null && headerMap.containsKey("x-death")) {
                Object xDeath = headerMap.get("x-death");
                if (xDeath instanceof Iterable) {
                    for (Object entry : (Iterable<?>) xDeath) {
                        if (entry instanceof Map) {
                            Object countObj = ((Map<?, ?>) entry).get("count");
                            if (countObj instanceof Number) {
                                return ((Number) countObj).intValue();
                            }
                        }
                    }
                }
            }
        }
        return task.retryCount();
    }

    private java.util.concurrent.CompletionStage<Void> handleRetryOrDeadLetter(Message<JsonObject> message, AiSummaryTask task,
                                                                               int currentRetryCount, int allowedMaxRetries, Exception ex) {
        if (currentRetryCount < allowedMaxRetries) {
            int nextRetryCount = currentRetryCount + 1;
            log.warn("{} 重试 {}/{}，postId={}", MQ_TAG, nextRetryCount, allowedMaxRetries, task.postId());
            try {
                taskProducer.sendSummaryTask(task.postId(), task.content(), task.priority(), nextRetryCount, task.performedBy());
                return safeAck(message);
            } catch (Exception sendEx) {
                log.error("{} 重新发布任务失败，进行 NACK，postId={}", MQ_TAG, task.postId(), sendEx);
                return safeNack(message, sendEx);
            }
        } else {
            log.error("{} 达到最大重试次数，进行 NACK 进入死信，postId={}", MQ_TAG, task.postId());
            return safeNack(message, ex);
        }
    }

    /**
     * 在新事务中保存摘要结果和审计日志。
     * 在 @Blocking 工作线程中调用，允许使用 Panache 阻塞 API。
     */
    private void saveAiSummaryAndAuditLog(Long postId, AiResult aiResult, long durationMs, Long userId) {
        if (aiResult == null || aiResult.contents.isEmpty()) {
            log.warn("{} AI 返回摘要为空，跳过保存，postId={}", MQ_TAG, postId);
            return;
        }

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
            @Override
            public void run() {
                Post post = Post.findById(postId);
                if (post == null) {
                    log.warn("{} 文章不存在，跳过，postId={}", MQ_TAG, postId);
                    return;
                }

                // 如果状态为锁定(1)或禁用(2)，不覆盖
                if (post.aiSummaryStatus != null && post.aiSummaryStatus > 0) {
                    log.info("{} AI 摘要已锁定/禁用（status={}），跳过，postId={}", MQ_TAG, post.aiSummaryStatus, postId);
                    return;
                }

                // 写审计日志
                com.biliwind.blog.model.AuditLog auditLog = new com.biliwind.blog.model.AuditLog();
                auditLog.entityType = "post";
                auditLog.entityId = postId;
                auditLog.action = "ai_summary_generated";
                Map<String, Object> oldVal = new HashMap<>();
                if (post.aiSummary != null) {
                    for (Map.Entry<String, String> entry : post.aiSummary.entrySet()) {
                        oldVal.put(entry.getKey(), entry.getValue());
                    }
                }
                auditLog.oldValue = oldVal;

                Map<String, Object> newVal = new HashMap<>();
                for (Map.Entry<String, String> entry : aiResult.contents.entrySet()) {
                    newVal.put(entry.getKey(), entry.getValue());
                }
                auditLog.newValue = newVal;

                // 记录 AI 消耗详情
                auditLog.durationMs = durationMs;
                auditLog.inputTokens = aiResult.inputTokens;
                auditLog.outputTokens = aiResult.outputTokens;
                auditLog.totalTokens = aiResult.totalTokens;

                if (userId != null) {
                    auditLog.performedBy = com.biliwind.blog.model.User.findById(userId);
                }

                auditLog.persist();

                // 更新文章摘要
                post.aiSummary = aiResult.contents;
                post.persist();
                log.info("{} 已保存 AI 摘要并写入审计日志，postId={}", MQ_TAG, postId);
            }
        });
    }

    private java.util.concurrent.CompletionStage<Void> safeAck(Message<JsonObject> message) {
        try {
            return message.ack();
        } catch (Exception e) {
            log.error("{} ACK 失败: {}", MQ_TAG, e.getMessage());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private java.util.concurrent.CompletionStage<Void> safeNack(Message<JsonObject> message, Throwable reason) {
        try {
            return message.nack(reason);
        } catch (Exception e) {
            log.error("{} NACK 失败，改为 ACK: {}", MQ_TAG, e.getMessage());
            return safeAck(message);
        }
    }
}
