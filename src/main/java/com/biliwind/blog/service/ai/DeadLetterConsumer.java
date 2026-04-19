package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.DeadLetterMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);
    private static final String MQ_TAG = "[MQ]";
    private static final String ERROR_MARK = "!!!!";
    private static final String WARN_MARK = "----";

    private void logMqWarn(String marker, String message, Object... args) {
        log.warn("{} {} {}", MQ_TAG, marker, String.format(message, args));
    }

    private void logMqError(String marker, String message, Object... args) {
        log.error("{} {} {}", MQ_TAG, marker, String.format(message, args));
    }

    @Incoming("ai-summary-dead-letter-in")
    @Transactional
    public CompletionStage<Void> consume(Message<AiSummaryTask> message) {
        AiSummaryTask task = message.getPayload();

        logMqWarn(WARN_MARK, "收到死信消息，postId=%d, priority=%d, retryCount=%d",
                task.postId(), task.priority(), task.retryCount());

        try {
            saveDeadLetterMessage(task);

            logMqWarn(WARN_MARK, "死信消息详情，postId=%d, priority=%d, 重试次数=%d, 任务内容语言数=%d",
                    task.postId(), task.priority(), task.retryCount(),
                    task.content() != null ? task.content().size() : 0);

            message.ack().toCompletableFuture().join();

            logMqWarn(WARN_MARK, "死信消息已处理，postId=%d", task.postId());

        } catch (Exception e) {
            logMqError(ERROR_MARK, "处理死信消息时发生异常，postId=%d, error=%s", task.postId(), e.getMessage(), e);
            try {
                message.ack().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    private void saveDeadLetterMessage(AiSummaryTask task) {
        try {
            DeadLetterMessage dlm = new DeadLetterMessage();
            dlm.sourceQueue = "ai-summary-tasks";
            dlm.exchangeName = "ai-tasks-dlx";
            dlm.routingKey = "summary-dead";
            dlm.postId = task.postId();
            dlm.priority = task.priority();
            dlm.retryCount = task.retryCount();
            dlm.errorReason = "AI 摘要处理失败（超过最大重试次数或处理超时）";

            Map<String, Object> content = new HashMap<>();
            content.put("postId", task.postId());
            content.put("priority", task.priority());
            content.put("retryCount", task.retryCount());
            content.put("content", task.content());
            dlm.messageContent = content;

            dlm.deadLetteredAt = Instant.now();
            dlm.persist();

            logMqWarn(WARN_MARK, "已保存死信消息记录，id=%d, postId=%d", dlm.id, task.postId());
        } catch (Exception e) {
            logMqError(ERROR_MARK, "保存死信消息记录失败，postId=%d, error=%s", task.postId(), e.getMessage(), e);
        }
    }
}
