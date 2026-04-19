package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.Post;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AiTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskConsumer.class);
    private static final String MQ_TAG = "[MQ]";
    private static final String ARROW_IN = ">>>>";
    private static final String ARROW_OUT = "<<<<";
    private static final String ERROR_MARK = "!!!!";
    private static final String WARN_MARK = "----";

    private static final String MAX_RETRIES_ENV = "AI_SUMMARY_MAX_RETRIES";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long TASK_TIMEOUT_SECONDS = 60;
    @Inject
    Instance<AiService> aiServiceInstances;

    @Inject
    AiTaskProducer taskProducer;

    @Inject
    AiManager aiManager;
    private volatile int maxRetries;

    @PostConstruct
    void init() {
        log.info("{} AI Task Consumer 初始化完成", MQ_TAG);
        log.info("{} 检测到 AI 服务数量: {}", MQ_TAG, aiServiceInstances.stream().count());
        for (AiService svc : aiServiceInstances) {
            log.info("{}   - {}: available={}, priority={}",
                    MQ_TAG,
                    svc.getClass().getSimpleName(),
                    svc.isAvailable(),
                    svc.getPriority());
        }
    }

    private int getMaxRetries() {
        if (maxRetries == 0) {
            String envValue = System.getenv(MAX_RETRIES_ENV);
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

    @Incoming("ai-summary-tasks-in")
    public CompletionStage<Void> consume(Message<AiSummaryTask> message) {
        log.info("{} 收到消息，channel=ai-summary-tasks-in", MQ_TAG);

        AiSummaryTask task = message.getPayload();

        int currentRetryCount = getRetryCount(message, task);
        int allowedMaxRetries = getMaxRetries();

        log.info("{} 处理任务，postId={}, priority={}, currentRetry={}, maxRetries={}",
                MQ_TAG, task.postId(), task.priority(), currentRetryCount, allowedMaxRetries);

        if (!validateTask(task)) {
            log.error("{} 任务验证失败，确认跳过，postId={}", MQ_TAG, task.postId());
            return ackMessage(message);
        }

        CompletableFuture<Void> resultFuture = new CompletableFuture<Void>();

        try {
            CompletionStage<Map<String, String>> aiFuture = aiManager.summarize(task.content());

            aiFuture.toCompletableFuture().orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete(new SummarizeResultHandler(message, task, currentRetryCount, allowedMaxRetries, resultFuture));

        } catch (Exception consumeEx) {
            log.error("{} 消费异常，postId={}", MQ_TAG, task.postId(), consumeEx);
            CompletionStage<Void> nackStage = handleFailureOrNack(message, task, currentRetryCount, allowedMaxRetries, consumeEx);
            nackStage.toCompletableFuture().complete(null);
        }

        return resultFuture;
    }

    private int getRetryCount(Message<AiSummaryTask> message, AiSummaryTask task) {
        Optional<IncomingRabbitMQMetadata> metadata = message.getMetadata(IncomingRabbitMQMetadata.class);
        if (metadata.isPresent()) {
            IncomingRabbitMQMetadata rmqMeta = metadata.get();
            Map<String, Object> headerMap = rmqMeta.getHeaders();
            if (headerMap != null) {
                if (headerMap.containsKey("x-death")) {
                    Object xDeath = headerMap.get("x-death");
                    if (xDeath instanceof Iterable) {
                        int count = 0;
                        for (Object entry : (Iterable<?>) xDeath) {
                            if (entry instanceof Map) {
                                Map<?, ?> entryMap = (Map<?, ?>) entry;
                                Object countObj = entryMap.get("count");
                                if (countObj instanceof Number) {
                                    count = ((Number) countObj).intValue();
                                }
                            }
                        }
                        return count;
                    }
                }
            }
        }
        return task.retryCount();
    }

    private CompletionStage<Void> handleFailureOrNack(Message<AiSummaryTask> message, AiSummaryTask task,
                                                      int currentRetryCount, int allowedMaxRetries, Throwable ex) {

        if (currentRetryCount < allowedMaxRetries) {
            int nextRetryCount = currentRetryCount + 1;
            log.warn("{} 重试，postId={}, retry={}/{}",
                    MQ_TAG, task.postId(), nextRetryCount, allowedMaxRetries);

            try {
                taskProducer.sendSummaryTask(task.postId(), task.content(), task.priority(), nextRetryCount);
                return ackMessage(message);
            } catch (Exception publishEx) {
                log.error("{} 重新发布失败，postId={}", MQ_TAG, task.postId(), publishEx);
                return nackMessage(message, ex);
            }
        } else {
            log.error("{} 达到最大重试次数，进入死信队列，postId={}",
                    MQ_TAG, task.postId());
            return nackMessage(message, ex);
        }
    }

    private boolean validateTask(AiSummaryTask task) {
        if (task.postId() == null) {
            log.error("{} 验证失败：postId 为空", MQ_TAG);
            return false;
        }
        if (task.content() == null || task.content().isEmpty()) {
            log.error("{} 验证失败：content 为空，postId={}", MQ_TAG, task.postId());
            return false;
        }
        Post post = Post.findById(task.postId());
        if (post == null) {
            log.error("{} 验证失败：文章不存在，postId={}", MQ_TAG, task.postId());
            return false;
        }
        return true;
    }

    private CompletionStage<Void> ackMessage(Message<AiSummaryTask> message) {
        log.info("{} ACK 消息", MQ_TAG);
        try {
            return message.ack().toCompletableFuture();
        } catch (Exception ackEx) {
            log.error("{} ACK 失败", MQ_TAG, ackEx);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletionStage<Void> nackMessage(Message<AiSummaryTask> message, Throwable reason) {
        log.info("{} NACK 消息，reason={}", MQ_TAG, reason.getMessage());
        try {
            return message.nack(reason).toCompletableFuture();
        } catch (Exception nackEx) {
            log.error("{} NACK 失败，尝试 ACK", MQ_TAG, nackEx);
            try {
                return message.ack().toCompletableFuture();
            } catch (Exception ackEx) {
                log.error("{} ACK 也失败", MQ_TAG, ackEx);
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private void updatePostAiSummary(Long postId, Map<String, String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            log.warn("{} AI 摘要为空，跳过更新", MQ_TAG);
            return;
        }
        Post post = Post.findById(postId);
        if (post != null) {
            post.aiSummary = summaries;
            post.persist();
            log.debug("{} 已更新文章 AI 摘要，postId={}", MQ_TAG, postId);
        } else {
            log.warn("{} 未找到文章，postId={}", MQ_TAG, postId);
        }
    }

    private class SummarizeResultHandler implements java.util.function.BiConsumer<Map<String, String>, Throwable> {

        private final Message<AiSummaryTask> message;
        private final AiSummaryTask task;
        private final int currentRetryCount;
        private final int allowedMaxRetries;
        private final CompletableFuture<Void> resultFuture;

        SummarizeResultHandler(Message<AiSummaryTask> message, AiSummaryTask task,
                               int currentRetryCount, int allowedMaxRetries, CompletableFuture<Void> resultFuture) {
            this.message = message;
            this.task = task;
            this.currentRetryCount = currentRetryCount;
            this.allowedMaxRetries = allowedMaxRetries;
            this.resultFuture = resultFuture;
        }

        @Override
        public void accept(Map<String, String> summaries, Throwable ex) {
            if (ex != null) {
                log.error("{} AI 处理失败，postId={}, error={}",
                        MQ_TAG, task.postId(), ex.getMessage(), ex);
                CompletionStage<Void> nackStage = handleFailureOrNack(message, task, currentRetryCount, allowedMaxRetries, ex);
                nackStage.toCompletableFuture().complete(null);
            } else {
                try {
                    updatePostAiSummary(task.postId(), summaries);
                    log.info("{} AI 处理成功，postId={}", MQ_TAG, task.postId());
                    ackMessage(message);
                    resultFuture.complete(null);
                } catch (Exception updateEx) {
                    log.error("{} 更新摘要失败，postId={}", MQ_TAG, task.postId(), updateEx);
                    CompletionStage<Void> nackStage = handleFailureOrNack(message, task, currentRetryCount, allowedMaxRetries, updateEx);
                    nackStage.toCompletableFuture().complete(null);
                }
            }
        }
    }
}
