package com.biliwind.blog.service.ai;

import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class AiTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskProducer.class);

    @Inject
    @Channel("ai-summary-tasks")
    Instance<Emitter<AiSummaryTask>> taskEmitter;

    @Inject
    @Channel("ai-audit-tasks")
    Instance<Emitter<AiAuditTask>> auditEmitter;

    @ConfigProperty(name = "windblog.outbox.publish-timeout", defaultValue = "30S")
    Duration publishTimeout;

    public void sendSummaryTask(AiSummaryTask task) {
        int rabbitPriority = convertToRabbitPriority(task.priority());

        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withPriority(rabbitPriority)
                .build();

        log.info("发送 AI 摘要任务到队列，postId={}, priority={}, retryCount={}",
                task.postId(), task.priority(), task.retryCount());

        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        Message<AiSummaryTask> msg = Message.of(task, Metadata.of(metadata), () -> {
            acknowledgement.complete(null);
            return CompletableFuture.completedFuture(null);
        }).withNack(error -> {
            acknowledgement.completeExceptionally(error);
            return CompletableFuture.completedFuture(null);
        });
        taskEmitter.get().send(msg);
        awaitPublish(acknowledgement);
    }

    public void sendSummaryTask(Long postId, Map<String, String> content, int priority, int retryCount, Long performedBy) {
        AiSummaryTask task = new AiSummaryTask(postId, content, priority, retryCount, performedBy);
        sendSummaryTask(task);
    }

    public void sendAuditTask(Long commentId, String content) {
        sendAuditTask(new AiAuditTask(commentId, content, 0));
    }

    public void sendAuditTask(AiAuditTask task) {
        log.info("发送 AI 审核任务到队列，commentId={}, retryCount={}", task.commentId(), task.retryCount());
        awaitPublish(auditEmitter.get().send(task));
    }

    public boolean resendFailedTask(AiSummaryTask originalTask) {
        try {
            AiSummaryTask retryTask = originalTask.withRetry();
            sendSummaryTask(retryTask);
            log.info("已重新发送失败任务，postId={}, 当前重试次数={}",
                    originalTask.postId(), retryTask.retryCount());
            return true;
        } catch (Exception e) {
            log.error("重新发送任务失败，postId={}", originalTask.postId(), e);
            return false;
        }
    }

    private int convertToRabbitPriority(int priority) {
        if (priority == 0) {
            return 10;
        } else if (priority == 1) {
            return 5;
        } else {
            return 0;
        }
    }

    private void awaitPublish(CompletionStage<?> completion) {
        if (completion == null) {
            throw new IllegalStateException("消息发布器未返回确认结果");
        }
        long timeoutMillis = Math.max(1000L, publishTimeout.toMillis());
        try {
            completion.toCompletableFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RabbitMQ 消息确认时被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("RabbitMQ 消息未在期限内确认", exception);
        }
    }
}
