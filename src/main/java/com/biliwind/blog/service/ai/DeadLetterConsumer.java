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

    @Incoming("ai-summary-dead-letter-in")
    @Transactional
    public CompletionStage<Void> consume(Message<AiSummaryTask> message) {
        AiSummaryTask task = message.getPayload();

        log.warn("收到死信消息：postId={}, priority={}, retryCount={}",
                task.postId(), task.priority(), task.retryCount());

        try {
            saveDeadLetterMessage(task);

            log.error("死信消息详情 - postId={}, priority={}, 重试次数={}, 任务内容={}",
                    task.postId(), task.priority(), task.retryCount(), task.content());

            message.ack().toCompletableFuture().join();

            log.info("死信消息已处理，postId={}", task.postId());

        } catch (Exception e) {
            log.error("处理死信消息时发生异常", e);
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

            log.info("已保存死信消息记录，id={}, postId={}", dlm.id, task.postId());
        } catch (Exception e) {
            log.error("保存死信消息记录失败，postId={}", task.postId(), e);
        }
    }
}
