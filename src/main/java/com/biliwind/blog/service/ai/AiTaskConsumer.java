package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.Post;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
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

    private static final String MAX_RETRIES_ENV = "AI_SUMMARY_MAX_RETRIES";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long TASK_TIMEOUT_SECONDS = 60;
    @Inject
    AiTaskProducer taskProducer;

    @Inject
    AiManager aiManager;
    private volatile int maxRetries;

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
        AiSummaryTask task = message.getPayload();

        int currentRetryCount = getRetryCount(message, task);
        int allowedMaxRetries = getMaxRetries();

        log.info("收到 AI 摘要任务，postId={}, priority={}, currentRetry={}, maxRetries={}",
                task.postId(), task.priority(), currentRetryCount, allowedMaxRetries);

        if (!validateTask(task)) {
            log.error("任务验证失败，确认跳过此消息，postId={}", task.postId());
            return ackMessage(message);
        }

        CompletableFuture<Void> resultFuture = new CompletableFuture<Void>();

        try {
            CompletionStage<Map<String, String>> aiFuture = aiManager.summarize(task.content());

            aiFuture.toCompletableFuture().orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete(new SummarizeResultHandler(message, task, currentRetryCount, allowedMaxRetries, resultFuture));

        } catch (Exception consumeEx) {
            log.error("消费消息时发生异常，postId={}", task.postId(), consumeEx);
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
            if (headerMap != null && headerMap.containsKey("x-death")) {
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
        return task.retryCount();
    }

    private CompletionStage<Void> handleFailureOrNack(Message<AiSummaryTask> message, AiSummaryTask task,
                                                      int currentRetryCount, int allowedMaxRetries, Throwable ex) {

        if (currentRetryCount < allowedMaxRetries) {
            int nextRetryCount = currentRetryCount + 1;
            log.warn("任务处理失败，将重新发布进行重试，postId={}, currentRetry={}, nextRetry={}, maxRetries={}",
                    task.postId(), currentRetryCount, nextRetryCount, allowedMaxRetries);

            try {
                taskProducer.sendSummaryTask(task.postId(), task.content(), task.priority(), nextRetryCount);
                return ackMessage(message);
            } catch (Exception publishEx) {
                log.error("重新发布消息失败，postId={}", task.postId(), publishEx);
                return nackMessage(message, ex);
            }
        } else {
            log.error("任务处理失败，已达到最大重试次数，发送到死信队列，postId={}, retryCount={}, maxRetries={}",
                    task.postId(), currentRetryCount, allowedMaxRetries);
            return nackMessage(message, ex);
        }
    }

    private boolean validateTask(AiSummaryTask task) {
        if (task.postId() == null) {
            log.error("任务验证失败：postId 为空");
            return false;
        }
        if (task.content() == null || task.content().isEmpty()) {
            log.error("任务验证失败：content 为空，postId={}", task.postId());
            return false;
        }
        Post post = Post.findById(task.postId());
        if (post == null) {
            log.error("任务验证失败：文章不存在，postId={}", task.postId());
            return false;
        }
        return true;
    }

    private CompletionStage<Void> ackMessage(Message<AiSummaryTask> message) {
        try {
            return message.ack().toCompletableFuture();
        } catch (Exception ackEx) {
            log.error("确认消息失败", ackEx);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletionStage<Void> nackMessage(Message<AiSummaryTask> message, Throwable reason) {
        try {
            return message.nack(reason).toCompletableFuture();
        } catch (Exception nackEx) {
            log.error("NACK 消息失败，尝试执行 ACK 跳过", nackEx);
            try {
                return message.ack().toCompletableFuture();
            } catch (Exception ackEx) {
                log.error("最终 ACK 也失败", ackEx);
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private void updatePostAiSummary(Long postId, Map<String, String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            log.warn("AI 摘要为空，跳过更新，postId={}", postId);
            return;
        }
        Post post = Post.findById(postId);
        if (post != null) {
            post.aiSummary = summaries;
            post.persist();
            log.debug("已更新文章 AI 摘要，postId={}, 摘要语言数={}", postId, summaries.size());
        } else {
            log.warn("未找到文章，postId={}", postId);
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
                log.error("AI 摘要生成失败或超时，postId={}, 错误：{}",
                        task.postId(), ex.getMessage(), ex);
                CompletionStage<Void> nackStage = handleFailureOrNack(message, task, currentRetryCount, allowedMaxRetries, ex);
                nackStage.toCompletableFuture().complete(null);
            } else {
                try {
                    updatePostAiSummary(task.postId(), summaries);
                    log.info("AI 摘要任务处理成功，postId={}", task.postId());
                    ackMessage(message);
                    resultFuture.complete(null);
                } catch (Exception updateEx) {
                    log.error("更新文章摘要失败，postId={}", task.postId(), updateEx);
                    CompletionStage<Void> nackStage = handleFailureOrNack(message, task, currentRetryCount, allowedMaxRetries, updateEx);
                    nackStage.toCompletableFuture().complete(null);
                }
            }
        }
    }
}
